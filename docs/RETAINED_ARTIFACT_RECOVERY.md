# Восстановление исторических APK и generated-assets

Исторические APK и сгенерированные изображения хранятся отдельным зашифрованным
архивом в принадлежащем проекту независимом хранилище резервных копий. Исходный
код импортера и JSON-манифесты остаются в Git. Архив не является источником
новых сборок, не содержит production environment или ключ Android-подписи и
не подменяет резервные копии БД и действующего S3-контента приложения.

`infrastructure/artifact-recovery/manifest.json` перечисляет исходный Git commit,
пути, Git blob IDs, размеры, режимы файлов и SHA-256 каждого файла. Соседние
receipts связывают манифест с точной версией объекта и подтверждённым
восстановлением на другом компьютере. Они не содержат ключей доступа или
ключа расшифрования. Приватные endpoint, bucket и учётные данные берутся только
из защищённого environment; отпечаток адреса хранилища в receipt предотвращает
случайное обращение к другому bucket.

## Формат и защита

Операторский инструмент —
[`retained_artifacts.py`](../infrastructure/scripts/prod/retained_artifacts.py).
Он использует тот же chunked AES-256-GCM формат `OTZIVDB2`, что и
`DatabaseBackupService`, с существующим независимым
`BACKUP_ENCRYPTION_KEY_BASE64`. Для совместимости имеется фиксированный вектор,
независимо полученный через Java `Cipher`. В данном архиве расшифрованное
содержимое — **tar с артефактами, не SQL и не database backup**. Его нельзя
передавать скрипту восстановления базы данных.

Объект записывается в новый путь
`backup/<BACKUP_S3_PROJECT>/retained-artifacts/v1/<tar-sha256>/<ciphertext-sha256>.tar.otzivdb2`.
Запись требует `If-None-Match: *`; существующие объекты не перезаписываются.
Инструмент проверяет private ACL bucket и объекта, отказ в анонимном GET,
отсутствие применимого удаления через lifecycle и точную версию для HEAD,
GET и GetObjectRetention. Настройки bucket не изменяются. При включённом
`BACKUP_S3_OBJECT_LOCK_ENABLED` срок и режим retention обязательны и сверяются
с ответом провайдера. SSE-S3 следует существующему backup policy; явный режим
совместимости `false` не отключает обязательное клиентское AES-GCM шифрование.

Ключ восстановления должен сохраняться независимо от объекта. При последующей
ротации ключа backup нужно сохранить старый ключ до проверенного переноса этого
архива на новый: обновление environment само по себе архив не перешифровывает.
Удаление lifecycle и окончание срока Object Lock — разные события: отсутствие
expiration оставляет объект в хранилище и после завершения минимальной защиты
от удаления. Новые политики lifecycle должны учитывать этот префикс.

## Восстановление на чистой машине

Нужны Python 3.11+ и доступ к защищённому environment с выделенными
`BACKUP_S3_*` и ключом backup. Эти секреты не нужны для CI, сборки приложения
или обычного тестирования. Установите зависимости в отдельное окружение:

```powershell
python -m venv .artifact-tools
.artifact-tools/Scripts/python -m pip install --require-hashes -r infrastructure/scripts/prod/retained-artifacts-requirements.txt
.artifact-tools/Scripts/python -B infrastructure/scripts/prod/retained_artifacts.py restore --env <protected-env-file> --manifest infrastructure/artifact-recovery/manifest.json --receipt infrastructure/artifact-recovery/storage-receipt.json --out <new-empty-recovery-directory>
```

В Linux executable окружения — `.artifact-tools/bin/python`. Используйте каталог
вне checkout: восстановление требует нового каталога и не накладывает архив
поверх существующих файлов. Успешный запуск означает проверку версии объекта,
размера и SHA-256 ciphertext, аутентификацию всех AES-GCM chunks, SHA-256 tar и
всех перечисленных файлов. В каталоге создаётся `restore-proof.json`.
Произвольные дополнительные tar entries, links, traversal, дубликаты и
неоднозначные имена Windows отклоняются.

Перед использованием восстановленного APK повторно запустите
`mobile/scripts/verify-android-release.ps1` с ожидаемыми package/version и
производственным отпечатком signer. Исторический `debug` code53 сохранён для
истории, но не пригоден для публикации; его manifest содержит `debuggable=true`.
Остальные восемь APK имеют коды 54–61. Выпуск новой версии собирается и
подписывается обычным release workflow, а не этим архиватором.

## Проверки без production credentials

```powershell
python -B -m unittest discover -s infrastructure/scripts/prod -p test_retained_artifacts.py
pwsh -NoProfile -File mobile/scripts/test-android-release-contracts.ps1
```

Первой команде нужна библиотека `cryptography` из закреплённых requirements.
Вторая требует Android SDK Build-Tools/platform и JDK. Она создаёт два маленьких
APK с временным тестовым signer, проверяет release/debug, package/version,
неверного signer, Unicode-путь и очистку временных файлов. Дополнительно
проверяется, что тестовый signer отклоняется производственным allowlist.
Исторические APK, Android release keystore и секреты хранилища ей не нужны.

Удаление файлов из текущего Git tree не удаляет старые Git blobs и не сокращает
уже существующую историю. Переписывание истории является отдельным решением.
