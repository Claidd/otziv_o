# Android release signing runbook

## Инварианты production-релиза

Production APK должен одновременно удовлетворять всем условиям:

- package name: `com.hunt.otziv`;
- один подписант;
- SHA-256 сертификата подписанта:
  `A15A162AFE1F808F9586DD3F129F9E61F4BE49CCFF708CA99C6A0714004251D5`;
- валидная APK Signature Scheme v2 для поддерживаемых Android 7+;
- внутренние `versionCode` и `versionName` совпадают с параметрами релиза;
- приложение не собрано как `debuggable`.

Отпечаток сертификата является публичным идентификатором и хранится в коде
проверки. JKS, его пароли и пароль ключа являются секретами и в репозиторий не
попадают.

## Сборка

Обычная release-сборка выполняется из каталога `mobile`:

```powershell
.\scripts\build-android-release.ps1 -VersionCode 62 -VersionName 1.0.62
```

Для production web bundle с включёнными push-уведомлениями добавляется `-Push`:

```powershell
.\scripts\build-android-release.ps1 -VersionCode 62 -VersionName 1.0.62 -Push
```

Скрипт выполняет Capacitor sync, передаёт версию в Gradle без редактирования
`build.gradle`, собирает release, проверяет подпись и внутренние метаданные, а
затем атомарно помещает APK в `mobile/builds`. Существующий файл того же
`versionCode` допускается только при полностью совпадающем SHA-256; подмена
артефакта той же версии запрещена.

По умолчанию Gradle читает `mobile/android/keystore.properties`. Другой файл
можно передать через `-KeystorePropertiesPath`. В нём обязательны четыре
непустых свойства:

```properties
storeFile=C:/secure/location/otziv-release.jks
storePassword=...
keyAlias=otziv
keyPassword=...
```

Сам `storeFile` должен существовать. `assembleRelease`, `bundleRelease` и другие
задачи, создающие release-артефакт, завершаются ошибкой до создания unsigned APK,
если конфигурация отсутствует или неполна. Debug, lint и unit-test задачи ключа
не требуют.

## Независимая проверка APK

Verifier сам находит `apksigner` и `aapt`/`aapt2` в Android SDK или `PATH`:

```powershell
.\scripts\verify-android-release.ps1 `
  -ApkPath .\builds\otziv-prod-release-v1.0.62-code62.apk `
  -ExpectedVersionCode 62 `
  -ExpectedVersionName 1.0.62
```

Keyless regression-набор использует уже выпущенный APK и не обращается к JKS:

```powershell
.\scripts\test-android-release-contracts.ps1
```

Он проверяет успешный release `1.0.61`, а также обязательный отказ при неверном
signer, package, `versionCode`, `versionName` и для legacy debuggable APK.

## Публикация

`infrastructure/scripts/prod/deploy-prod.ps1` запускает тот же verifier сразу
после выбора APK — до Docker build, формирования deploy bundle и обращения к
VPS. Имя файла используется только как заявленные метаданные: package, версия и
signer извлекаются из APK и обязаны совпасть. После копирования в bundle SHA-256
проверяется повторно.

Правила версии:

1. `versionCode` всегда положительный и строго растёт между новыми релизами.
2. `versionName` и `versionCode` внутри APK совпадают с именем файла.
3. Один `versionCode` обозначает один неизменяемый APK.
4. Для deploy без мобильного релиза используется `-SkipMobileApkUpload`.

## Хранение и восстановление ключа

- JKS хранится вне репозитория с доступом только владельцу и администраторам.
- Файл с паролями также должен находиться вне общедоступного рабочего каталога
  либо иметь отдельный ограниченный ACL.
- Нужны минимум две зашифрованные резервные копии в независимых местах; пароль
  к копии хранится отдельно.
- Restore drill выполняется на изолированной машине: восстановить копию,
  запустить `keytool -list -v` с интерактивным вводом пароля и сравнить SHA-256
  сертификата с указанным выше. В отчёте сохраняются только дата, результат и
  публичный отпечаток — не пути, alias или пароли.

Нельзя генерировать новый production key при ошибке доступа к текущему JKS.
Потеря исходного ключа нарушит обновление уже установленных sideload APK.

## Ротация signer

Этот rollout закрепляет текущий signer и не меняет ключ. Фактическая ротация —
отдельная высокорисковая операция: сначала нужна bridge-версия на старом ключе,
lineage APK Signature Scheme v3/v3.1 и проверка обновления на API 24, 28, 33 и
актуальной версии Android. До завершения такой матрицы approved fingerprint в
verifier менять нельзя.
