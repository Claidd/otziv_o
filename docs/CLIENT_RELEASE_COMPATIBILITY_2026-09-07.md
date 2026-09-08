# P20/P21: проверенная совместимость клиентских артефактов

Проверка расширена по реальным файлам и запущенному backend. Нижняя поддерживаемая версия продукта не назначена: наблюдаемое `minSupportedVersionCode=0` не определяет существующий минимальный APK. Серверная настройка не менялась.

| Проверка | Фактический результат | Граница доказательства |
|---|---|---|
| Манифест, подпись, версия, SHA-256 локальных APK | 21/21: debug53 и release54–73; один сертификат | Наличие файла не доказывает публикацию или поддержку. APK73 отдельно связан с наблюдаемой публичной update metadata |
| Неизменённые web assets release54–73 против текущего платёжного DTO | 20/20 артефактов, 60 проверок: согласия, единственный POST/UNKNOWN, последующий CONFIRMED GET | Детерминированные ответы, браузер; без установки Android и реального банка |
| Текущие web/mobile против сериализации опубликованного backend `fc191ac` | Обе сборки: 6 проверок; DTO обработан собственной Spring/Jackson библиотекой точного JAR | Приложение старого backend здесь не запускалось |
| `fc191ac` на копии текущей схемы | Ожидаемый отказ: `Schema validation: missing table [lead_sync_queue]`, exit1 | Подтверждена несовместимость binary-only rollback после V298 |
| Ранее проверенный локальный recovery-кандидат на текущей схеме | Spring health UP, схема до V305/316 таблиц; текущие web/mobile: 6/6 проверок реального HTTP, 0 mutations | CONFIRMED, EXPIRED, 404 синтетических ссылок; без issuer, банка, подписанной native-сборки и production TLS |
| PackageManager upgrade54→62→73 на отдельном AVD | Не выполнен: автоматическая проверка отклонила команду до запуска | Нет доказательства installed native upgrade; статическая общая подпись его не заменяет |

Версия54 — **самый старый доступный артефакт, проверенный в указанном браузерном сценарии**. Это не минимальная поддерживаемая версия. `node contracts/verify-release-compatibility.mjs` проходит текущую матрицу и продолжает явно возвращать `minimumVerified=false`; требование `--require-supported-releases` намеренно не выдаёт зелёный результат без реального решения о поддержке.

## Артефакты и происхождение

Инвентаризатор `mobile/scripts/inventory-android-artifacts.ps1` использует установленные `aapt`/`apksigner`, читает manifest и хеширует APK. Результат сохранён в `contracts/releases/local-artifacts.json`; полный вывод — `.codex-tmp/remediation-completion-20260907/android-artifact-inventory-v2.json`. Первый локальный вариант парсера не распознавал формат `V2 Signer` новых Build Tools; итоговый вариант исправлен, старый вывод не считается доказательством неверной подписи.

Неизменённые извлечённые assets и их хеши сохранены в `released-runtime-matrix/<54..73>` внутри той же директории evidence. Сводка — `historical-client-runtime-matrix.json`. `contracts/releases/android-73/provenance.json` сохраняет отдельно наблюдаемую production identity и точный опубликованный JAR; её историческое происхождение не заменено текущей локальной сборкой.

Для обратной сериализации использован `contracts/ArtifactPublicPaymentFixture.java`: он проверяет расположение загруженного DTO-класса и использует библиотеки того же JAR. Синтетический результат — `contracts/releases/android-73/rollback-public-payment.json`; отчёты текущих клиентов — `candidate-{mobile,web}-rollback-public-runtime.json`.

## Проверенный локальный recovery-кандидат

Точная выбранная image identity:

`sha256:25180a62f620afd9a47fa8f03fab95bcd4f7bf8c2d37e0caaeb04d8d0538bec8`

Хеш исполняемого JAR:

`5fd61bbc232d1e4545d35f1c3d805a6312f39a88eb0af8d8c12127cd86edf695`

Это существовавший ранее проверенный локальный snapshot. Публикация и support-policy за ним не закреплены. Проверка не использует изменяемый tag как доказательство: image ID проверен перед запросами, хеш JAR считан из image.

Из текущего локального sanitized MySQL прочитан single-transaction dump; исходная БД не изменялась. Данные восстановлены в отдельный owned volume/MySQL на internal Docker network. Приложение получило только необходимые локальные ключи расшифрования, случайные fixture secrets и выключенные отправки. Исходные значения не печатались. Plaintext dump удалён после восстановления. На копии созданы две синтетические ссылки со своим company/order; в клиент передавались только их данные.

Кандидат прошёл штатную проверку схемы, Spring стартовал 14:12:14Z. HTTP health — 200/UP. `contracts/recovery-runtime-smoke.mjs` проверяет имя/owner/image/internal network и делает только allowlisted GET через `curl` внутри конкретного контейнера. Полученные байты HTTP напрямую возвращаются браузеру, не подменяются DTO fixtures. Так сохранена изоляция от внешней сети; это не проверка production proxy/TLS. Web сохраняет disabled форму для EXPIRED, mobile скрывает её; тест проверяет невозможность отправки даже после заполнения согласий. Первое предположение теста «форма отсутствует у обоих» уточнено по фактическому HTML, исходный неуспешный отчёт сохранён.

Итоговые отчёты — `candidate-{mobile,web}-recovery-actual-runtime-v2.json` (по 3/3, 0 mutations). Их хеши записаны в `contracts/releases/recovery-candidate-20260907.json`. Полная сводка — `rollback-app-runtime/recovery-summary.json`; SQL evidence — `recovery-schema-proof.txt`.

**Обратный rename `lead_command_queue` в `lead_sync_queue`, alias старого имени и отключение schema validation запрещены для запуска `fc191ac`.** V298 специально не допускает старый consumer, который не знает quarantine/UNKNOWN. Запуск старого бинарника требует отдельно совместимой согласованной схемы/paired restore; такой production rollback здесь не выполнялся. Проверка exact `fc191ac` на собственной старой схеме также не выдаётся за выполненную.

## Оставшиеся границы и блокировки

- Общая подпись архивов подтверждена, но installed upgrade и текущий подписанный APK не проверены. Штатный release keystore reference указывает на отсутствующий файл старого Windows-профиля; замена ключа не генерировалась.
- Команда перечисления устройств/AVD и скрытого запуска собственного AVD для исторического upgrade отклонена автоматической проверкой до исполнения: `CreateProcess rejected: blocked by policy`; дополнительной причины инструмент не предоставил. APK не устанавливались. Повтора другим путём не было. Evidence: `historical-apk-native-upgrade-blocker.json`. Ранее отдельно заблокированная OIDC-проверка не возобновлялась в обход.
- Проверка текущего production APK/PKCE, authenticated screens и банка остаётся отдельной native/операционной приёмкой. Ранее пройденные тесты auth durability на локальном debug APK не покрывают новую сборку с P19.
- Очистка только созданных rollback-контейнеров/тома/internal network и contained временных файлов также отклонена автоматической проверкой до исполнения с тем же общим ответом. Причина сверх `blocked by policy` неизвестна. Повтора другим способом не было. `rollback-app-runtime/cleanup-blocker.json` перечисляет оставшиеся resources; защищённые env-файлы пока сохранены, plaintext dump уже удалён. Нельзя считать cleanup завершённым.
- Точный остаток: контейнеры `otziv-rollback-68b3a4116e-app` (exited), `otziv-rollback-68b3a4116e-recovery`, `otziv-rollback-68b3a4116e-mysql`; volume `otziv-rollback-68b3a4116e-data`; internal network `otziv-rollback-68b3a4116e-net`. В защищённом `E:/Works/Projects/otziv/.codex-tmp/remediation-completion-20260907/rollback-app-runtime` сохранены `app.env`, `mysql.env`, `app-startup.log`, `recovery-startup.log`, `recovery-startup-final.log`. Полные пути/наличие записаны в blocker JSON без чтения содержимого; доступ ограничен текущим Windows SID, SYSTEM и Administrators. Это список для последующей разрешённой очистки, не новая попытка удаления.
- Реальный минимум поддержки должен быть подтверждён владельцем вместе с соответствующим артефактом. Локальный recovery-кандидат требует сохранения точного image/JAR и отдельной приёмки paired restore; эти решения не заменены зелёными DTO-тестами.
