# Выпуск на VPS: подготовка, переход БД и откат

Дата: 2026-09-09, Asia/Irkutsk. Статус: **подготовка к выпуску; production cutover не выполнен**. Этот документ не подтверждает изменение VPS. Финальный release commit и CI заполняет ответственный за выпуск после фиксации всего набора.

| Поле приёмки | Состояние |
| --- | --- |
| Точный release commit / проверенный source snapshot | **PENDING** |
| Финальные required CI на этом commit, ссылки на checks | **PENDING**; результаты публикации отдельных образов не заменяют release CI |
| Финальный штатный Docker smoke | PASS: новые требуемые images healthy, login21/21, password resets0; `.codex-tmp/release-readiness-20260908/final-pg-keycloak-prod-like-smoke.log` |
| Runtime security tests | 572 PASS; `.codex-tmp/release-readiness-20260908/final-runtime-security-tests-v2.log` |
| Registry/defaults validation в подготовленном дереве | 32 references PASS; повторить на финальном release snapshot |
| Образы MySQL / PostgreSQL / Keycloak и репетиции | Подтверждены ниже для точных опубликованных digest |
| Свежий согласованный production backup, независимая копия, empty-volume restore | **PENDING**, выполняются для текущего окна остановки записей |
| Операторская процедура production cutover под одним owner lock | **PENDING**: конкретные host/volume/backup/rollback inputs и проверка полного порядка; ordinary deploy в середине не используется |
| Production Go / открытие записи | **STOP** до закрытия всех строк выше и контрольных точек ниже |

Безопасный итог локальных проверок: `.codex-tmp/release-readiness-20260908/final-local-validation.json`, SHA-256 `25ef7f863e9a2acbfa100f62a5e6c2beba5affa1279d6a61c7805563353568b5`. Это фактически пройденные локальные проверки; финальный Git/CI release binding остаётся отдельной строкой. Scratch logs не входят автоматически в clean checkout и сохраняются в закрытом release evidence вместе с их проверяемым итогом.

## 1. Точный набор данных и образов

Исходное состояние взято из read-only VPS captures 2026-09-08. Перед окном работ его необходимо повторно сопоставить с фактическими image/config IDs, томами, схемами и конфигурацией; это историческое наблюдение, а не обещание, что сервер больше не менялся.

| Компонент | Наблюдённый источник VPS | Подготовленная цель |
| --- | --- | --- |
| MySQL | 9.0.0; Flyway `1.10.287`; `mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383` | 9.7.3; приложение выполняет append-only переход до `1.10.310`; `ghcr.io/claidd/otziv-security@sha256:3a3caaab4e71b3bfdec9da21c17c00ed10ca237151919aeddac8e5ce4b8b7baa` |
| PostgreSQL Keycloak | 17.10; `postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d` | 17.11; `ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf` |
| Keycloak | 26.2.5; `quay.io/keycloak/keycloak@sha256:4883630ef9db14031cde3e60700c9a9a8eaf1b5c24db1589d6a2d43de38ba2a9` | 26.7.3 с явно зафиксированным backport PR51945; `ghcr.io/claidd/otziv-security@sha256:c6ee6afd6750ae0886a608c5837085cb20417a6746ca2ed47b59b1a0fdec1973` |

Ожидаемые OCI configuration digests, дополнительно к полным references:

- MySQL: `sha256:80ee3b50147a329addbaf754abc4dce86dc06636624680d780351e2320a11070`.
- PostgreSQL: `sha256:d257683e1d6febdfb869b77c60683bc598b272788d60e65893a109adaf0a2db4`.
- Keycloak: `sha256:89f560e111619be776f984e2f317a775cb37ce20660271dbb25f638b454ffeab`.

Проект Compose — `otziv-prod`. Исходный MySQL volume — `docker_mysql_data`, mount `/var/lib/mysql`; исходный PostgreSQL volume — `otziv-prod_keycloak_pg_data`, mount `/var/lib/postgresql/data`. Зафиксировать перед работами их реальные Docker metadata и всех пользователей томов. Смена project name, mount path, `PGDATA`, subpath, UID или option file не является безобидной заменой образа. Старые тома не удалять. MinIO/Versity относится к локальному стенду и в этот VPS cutover не входит.

Проверяемые основания:

- [MySQL actual-result](../infrastructure/runtime-security/proofs/c14-mysql-vps/actual-result.json): 76 runtime checks, источник VPS287, реальное обновление движка и приложения, сохранение UNKNOWN/идентичностей доставки, restart и восстановление старой пары в отдельный пустой том. [Подробные границы](../infrastructure/runtime-security/proofs/c14-mysql-vps/README.md).
- [Итоговая PostgreSQL + Keycloak publication acceptance](../infrastructure/keycloak/security-generation/c14-migration-fix/proofs/publication-acceptance.json), SHA-256 `b4f1ce533dd116b4546d8fa6cd6e27731e357865d08cb100e9f33332511b1dfd`: exact published `078` + `c6ee`, 52 проверки на защищённой копии фактического источника, 20 issuer protocol checks, rollback и source continuity. Это заменяет вывод о блокирующем `93dd` в историческом `c14-postgres-vps/README.md`; старый failed proof сохраняется.
- [Свежая независимая проверка опубликованного Keycloak](../infrastructure/runtime-security/proofs/c14-keycloak-published/final/review.json): полный hosted raw scan 0 HIGH / 0 CRITICAL, SDK/dependency policy и rootfs binding; [PostgreSQL published proof](../infrastructure/runtime-security/proofs/c14-postgres-published/README.md) сохраняет raw 26 HIGH / 1 CRITICAL и точные проверенные классификации, дающие effective 0/0. Raw PostgreSQL findings не объявляются отсутствующими.
- [Activation registry](../infrastructure/runtime-security/reviewed-image-activations.json) связывает source manifests, publication, anonymous pull и переход БД. `production:false` / `vpsCutoverExecuted:false` в приёмке остаются существенными ограничениями.

## 2. Подготовка release без обращения к VPS

Команды ниже запускаются из корня **финального** проверенного checkout. Они перечислены как доступные механизмы; этот документ не утверждает, что они уже выполнены для ещё не выбранного release commit.

```powershell
node infrastructure/runtime-security/reviewed-image-defaults.mjs
.\infrastructure\scripts\prod\deploy-prod.ps1 -PrepareSnapshotOnly
```

`-PrepareSnapshotOnly` завершает работу до Docker build/push и SSH. При dirty deploy inputs скрипт создаёт и проверяет отдельный snapshot; при чистом checkout проверяет revision/lineage. Сам по себе этот флаг не означает новый полный CI run. Полная локальная проверка подготовленного snapshot доступна в [validate-deploy-snapshot.ps1](../infrastructure/scripts/prod/validate-deploy-snapshot.ps1) с обязательными `-RepoRoot` и `-BaseRevision`. Нельзя подставлять другой base/revision или использовать `-AllowDirtyWorktree`, `-SkipAutoSnapshotValidation`, `-FastAutoSnapshotValidation` для закрытия приёмки этого выпуска.

В закрытом release record должны быть: commit, CI check-run IDs и результаты именно этого revision, SHA deploy inputs/bundle, фактические APP/WEB/worker image digests/config IDs, APK SHA/versionCode при включении мобильного выпуска, env/secret-set recovery reference и предыдущая восстанавливаемая пара. Сами env, ключи, архивы БД и runtime tokens в Git/CI artifacts не помещаются.

Реальный путь доставки приложения — [deploy-prod.ps1](../infrastructure/scripts/prod/deploy-prod.ps1): Docker Compose build по `docker-compose.build.yaml`, push APP/WEB и опционального worker в указанный Docker Hub namespace, защищённый SCP bundle и SHA-проверенный rollout script. Тег содержит первые 12 символов deployment revision. `-SkipBuildPush` только использует уже опубликованные **теги**; это не отдельный интерфейс развёртывания APP/WEB по digest. Скрипт проверяет запущенный image ID против локально разрешённого на VPS тега, но не принимает заранее утверждённый APP/WEB digest как параметр. Поэтому перед выбором этой ветки требуется сохранить и сверить tag→digest/config с release record; одно совпадение имени тега недостаточно.

Изменённый WhatsApp собирается на VPS из полного source bundle и проходит Chromium sandbox preflight; неизменённый gateway переиспользуется. Его фактический image ID также включается в release record. `-EnableExternalReviewWorker` — явное включение, по умолчанию worker выключается; это решение о работающем production функционале должно совпадать с утверждённой конфигурацией. Без `-SkipMobileApkUpload` может быть выбран подписанный APK с наибольшим code из `mobile/builds`; намерение публиковать его фиксируется заранее.

Параметры соединения, проверенный host key, реальный `VpsPath`, `RemoteEnvFile`, external `ProjectFilesRoot`, пути SSH key и отдельного backup directory берутся из защищённой конфигурации оператора. Defaults `/opt/otziv` и `.env.prod` нельзя считать обнаруженным путём VPS: пример существующего deployment использует `/docker` и `.env`. Legacy [deploy-prod-ssh-images.ps1](../infrastructure/scripts/prod/deploy-prod-ssh-images.ps1) для этого выпуска не применяется.

## 3. Обязательный recovery point до изменения БД

Назначить одного владельца окна/lock и отдельного проверяющего Stop/Go. Зафиксировать допустимые downtime, RPO/RTO, предельное время решения об откате, хранение/доступ к ключам и правила обработки внешних эффектов после восстановления. Успешная репетиция на копии VPS не заменяет свежий backup при текущем остановленном write path.

1. Закрыть внешний ingress, остановить все backend/background/WhatsApp/worker writers, Keycloak login/admin/API writers и запись бизнес-объектов; учесть все реально работающие профили, ручные задания, callbacks и автоматические рестарты. Остановить/отключить `otziv-prod-up.timer`, дождаться остановки `otziv-prod-up.service` и удерживать `<VpsPath>/.deploy.lock.d` с проверенным owner. Сам lock запрещает self-heal reconciliation, но не останавливает Docker restart policies или сторонние writers; проверяются фактические процессы и DB sessions. MySQL events на время capture выключаются с сохранением исходного `ON` для последующего controlled resume.
2. Подтвердить завершение Spring/JPA/Hikari, отсутствие незавершённой работы и активных writers. У старого VPS app shell PID1 без `exec`: обычный `docker stop` не доказан как корректное завершение JVM. Проверенная репетиция идентифицировала единственный дочерний Java process перед TERM и проверяла полное завершение без forced kill/OOM. Точная production процедура завершения должна сохранять эти проверки; не использовать случайный PID.
3. Под одним наблюдаемым fence получить MySQL, PostgreSQL Keycloak и связанные object/secret/integration recovery sources. Сохранить backup hashes, точные immutable object versions, время каждого capture, image/schema IDs и независимо хранимые ключи. Восстановить из независимой копии в заранее выбранные **пустые изолированные** volumes со старыми MySQL9.0 / PostgreSQL17.10 и старым Keycloak26.2.5; доказать аутентификацию/decryption и связность данных. Не подключать старый binary к уже обновлённому тому.

Доступные форматы и пределы инструментов:

| Механизм | Что реально делает | Чего не доказывает |
| --- | --- | --- |
| [create-pre-deploy-db-backup.sh](../infrastructure/scripts/prod/create-pre-deploy-db-backup.sh) `create` / `verify` / `decrypt` | Предрелизный MySQL `OTZIV-PREDEPLOY-DB-V2`: AES-CBC/PBKDF2 + HMAC, schema defaults, hash и gzip verification. Deploy скачивает проверенный encrypted artifact вне Git | PostgreSQL, независимое S3 хранение, общий write fence и парное восстановление |
| Штатный MySQL backup + [R0 drill](R0_BACKUP_RESTORE_DRILL.md) | `OTZIVDB2`, authenticated encrypted object, remote receipt; изолированный empty-volume restore | Автоматическую согласованность с независимо снятым Keycloak backup |
| [recovery CLI](../infrastructure/recovery/README.md) `preflight` / `backup` / `manifest` / `postgres-drill` / `compare-identities` | PostgreSQL custom dump в `OTZIVPG1`, verified version-bound storage download, согласование receipts и PostgreSQL-only isolated restore | Автоматическую остановку writers, production cutover или полноценный production DR; `preflight` явно возвращает remote verification NOT_RUN |

Примеры **локального** восстановления уже полученных файлов, с ключами только из защищённого окружения:

```powershell
.\infrastructure\scripts\local\restore-backup-drill.ps1 -DumpPath $VerifiedMysqlBackup
node infrastructure/recovery/cli.mjs preflight $RecoveryConfig
node infrastructure/recovery/cli.mjs manifest $PairInput $PairManifest
node infrastructure/recovery/cli.mjs postgres-drill $RecoveryConfig $PairManifest $VerifiedPostgresBackup
node infrastructure/recovery/cli.mjs compare-identities $MysqlIdentityExport $KeycloakIdentityExport
```

Все переменные — реальные защищённые локальные пути, подготовленные оператором. MySQL R0 drill принимает `OTZIVDB2` или legacy gzip, **не** предрелизный `OTZIV-PREDEPLOY-DB-V2`. Для последнего сначала отдельная `verify`/`decrypt` процедура, затем проверка полученного gzip в restricted encrypted workspace; формат нельзя переименовать или напрямую передать в несовместимый drill. `manifest` ожидает полные remote-verified MySQL/PostgreSQL receipts; manifest предрелизного shell backup таким receipt не является. Если MySQL scheduled capture не может выполниться внутри общего fence, нужен отдельно проверенный способ получить такой recovery point — не второй production app с test-only run-once flag.

Recovery config требует Node22.12+, AWS CLI v2, pg_dump major17 и local Docker для drill; пароль/ключи передаются защищённым окружением/файлами. Секреты, signing material, database roles/global objects, object version inventory и integration state входят в парный manifest по [recovery README](../infrastructure/recovery/README.md). PostgreSQL `pg_dump` не включает cluster-wide roles. Приватные файлы остаются вне Git с ограниченным ACL; успешный `manifest` всё ещё сообщает FULL_SYSTEM_RESTORE_NOT_PROVEN.

Сохранить per-client WhatsApp `/auth` вместе с inbound inbox/history cursor, outbound operation ledger и remote-session journal. Результат отправки `UNKNOWN` не превращать в новый operation ID. После DB restore за более старую точку reconcile inbox/cursors/receipts и provider history **до** drain. Точные границы: [inbound runbook](WHATSAPP_INBOUND_DELIVERY_RUNBOOK.md), [remote session recovery](WHATSAPP_REMOTE_SESSION_RECOVERY.md). Восстановление парного auth state требует предусмотренной recovery policy: invalidate sessions и ротация signing keys до public access; это не автоматическая операция manifest builder.

## 4. Переход БД: контрольные точки Stop/Go

**Обычный deploy сейчас запускать нельзя.** [database_image_guard.py](../infrastructure/scripts/prod/database_image_guard.py) отвергнет target image при ещё работающем source9.0/17.10; `otziv-prod-up.sh` применяет тот же guard. Source activation в Git означает разрешённый кандидат, не разрешение обычному `compose up` изменить живую БД. Отсутствие DB container при существующем volume также не трактуется как свежая установка.

**G0 — до окна:** закрыты release/CI, backup/key custody и rollback capacity; exact current identities совпали. Проверена операторская процедура с одним владельцем lock до самого завершения. В [mysql-upgrade-rehearsal.mjs](../infrastructure/runtime-security/mysql-upgrade-rehearsal.mjs) есть только локальные capture/import-vps/rehearse, а в transition-readiness modules — проверка evidence. Они не являются production upgrade CLI.

**G1 — под fence, до открытия тома новым движком:** fresh encrypted pair успешно восстановлена на пустом стенде; все исходные writers остановлены; официальному MySQL Upgrade Checker предоставлены актуальные grants/plugins/definers/events/native options. Репетиция имела 0 errors, 23 рассмотренных warnings и 7 checks; любое новое расхождение требует разбора, не исключения check. PostgreSQL должен сохранить UTF8 / libc `en_US.utf8`, collation version2.41, применимые extensions и roles; у кандидата JIT отсутствует, допустимость для нагрузки фиксируется. MySQL `innodb_fast_shutdown=0` и clean shutdown обязательны перед передачей исходного data volume9.7.3.

**G2 — движки и issuer, ingress закрыт:** только один writer владеет каждым строго указанным томом; source volume и rollback volume не смешаны. MySQL native contract: UID/GID999:999, исходный `/entrypoint.sh`, `mysqld --user=999`, utf8mb4/utf8mb4_unicode_ci, `+08:00`, FK restriction OFF, GTID/enforcement OFF, binlog ON/ROW, reviewed retention, native socket и tmpfs UID999. Нет неизвестных option files, entrypoint override или скрывающего data mount. На подготовке events OFF; production steady-state ON после разрешения записи. PostgreSQL078 проверяется до запуска c6ee. Затем c6ee должен завершить настоящую миграцию, сохранить существующие realms/users/clients/credentials/roles и ожидаемые composites, пройти readiness и аутентификацию существующего пользователя без password reset. Нельзя игнорировать migration exception или удалять admin roles.

**G3 — приложение, всё ещё под fence:** миграции287→310, требуемые schema/history/count/identity проверки, encrypted credential read, issuer generation/login/refresh и contained write/restart canary проходят на фактических release images. Нет повторной отправки/списания, разрушения UNKNOWN или смены operation identity. Новые gateway начинают drain только после matching backend receipt schema; cutover history boundary задаётся явно. Сверяются реально работающие image/config IDs и сохраняется диагностическая metadata.

**G4 — открытие сервиса:** отдельное решение владельца выпуска после всех checks и rollback decision point. Вернуть только ранее разрешённые event/worker/automation политики, проверить login, основные read/write flows и метрики inbox/outbox/ledger, ошибки/латентность и фактический внешний маршрут alert. Только после этого завершить lock handoff и возобновить self-heal. При fail/OOM/timeout/несовпадении идентичностей сохранять fence, тома и частные диагностические файлы; не удалять lock для повторного запуска.

### Отдельная операторская процедура под одним lock

`deploy-prod.ps1` сам создаёт `.deploy.lock.d`; он не принимает внешний owner token и не имеет фазы «продолжить под lock координатора». После health checks он сам возобновляет timer и освобождает lock; обычный rollout также включает gateway и настройки внешних webhook. Поэтому выбранный путь — **отдельная ручная согласованная процедура**, без запуска deploy-prod между G0 и G4 и без передачи его внутреннего lock. Один оператор владеет lock, выполняет проверенные service-specific действия, фиксирует checks и завершает/откатывает всё окно. Самостоятельное удаление lock ради запуска обычного deploy запрещено порядком этой процедуры.

После фактически завершённого coordinated transition обычный deploy/self-heal может снова работать с уже существующими exact target image/config и тем же reviewed storage contract. Он не заменяет переход БД. Полный deployment invocation с real host/env/tag будет добавлен к release record только после закрытия этой точки; несуществующая команда `deploy --upgrade-db` здесь не предлагается.

Доступные building blocks для такого окна уже есть; новый production coordinator или новый backup format сейчас не вводятся:

1. **До окна на workstation:** подготовить snapshot и images существующими build/publication механизмами, сохранить rollback image archives и защищённый old/new env, получить независимую encrypted копию. Заполнить точные host/project/container IDs, volumes, native settings, owner и old timer state. На защищённой копии проверить последовательность G0–G4 и ошибки остановки, а не запускать local rehearsal против VPS.
2. **Capture под удерживаемым fence:** существующий shell backup вызывается как `bash infrastructure/scripts/prod/create-pre-deploy-db-backup.sh create my-mysql "$BACKUP_DIRECTORY" "$PROTECTED_ENV_FILE" "$RELEASE_TAG"`; PostgreSQL — `node infrastructure/recovery/cli.mjs backup "$RECOVERY_CONFIG"` после preflight и проверки доступности источника. Эти две команды выполняют реальные backup операции; здесь они только приведены для будущего окна. Shell backup не экспортирует PostgreSQL, роли и остальные recovery components. Его encrypted file **и** manifest копируются в отдельное защищённое хранилище, затем независимо скачиваются и проверяются штатным `verify`; PostgreSQL CLI сохраняет свой version-bound remote receipt.
3. **Restore gate:** для shell artifact доступны `verify <artifact> <manifest> <env-file>` и `decrypt <artifact> <manifest> <env-file> <new-output.sql.gz>`, затем R0 drill на локальном gzip/пустом MySQL9.0 volume. Для PostgreSQL — защищённый custom dump restore в пустой17.10, сохранённые role/bootstrap companions и старый issuer, как в уже выполненной actual-source репетиции. Нельзя делать вид, что несовместимый shell receipt прошёл `cli manifest`/`postgres-drill`: CLI принимает парный `OTZIVDB2` путь. При выборе shell backup весь фактический paired restore оформляется отдельным **операторским актом**, с hashes/versions/capture intervals и проверками обеих восстановленных БД; это не новый поддерживаемый формат CLI и не автоматический PASS.
4. **Service-specific переход:** оператор выполняет reviewed source shutdown/checker/native launch и issuer/application acceptance из G1–G3, с явным `--no-deps` там, где используется Compose, и с неизменным owner lock. Нельзя передать полному `compose up` выбор того, когда запускать приложение, issuer или writers. Точные commands/overrides разрешаются только после проверки фактической конфигурации VPS; произвольный флаг обхода ordinary image guard не добавляется. Сохранение old configuration/images/volumes и новый пустой rollback target входят в тот же план.
5. **Явное завершение:** после G4 сверить фактическое отсутствие незавершённого шага и owner lock, восстановить утверждённые events/worker policies, записать результат и удалить только принадлежащий этому окну lock. Возобновить timer в его утверждённом состоянии после проверки same-image guard. При неуспехе lock остаётся; восстановление старой пары выполняется по разделу5. Нельзя `rm -rf` угадываемого lock, выполнять broad volume cleanup или автоматически повторять неизвестный шаг.

**Точный оставшийся пробел:** полный ручной порядок для фактических host/volumes, fresh paired backup и его empty-volume restore ещё не выполнен как единое production maintenance окно. В репозитории есть отдельные проверенные механизмы и actual-source rehearsals, но нет команды, автоматически связывающей shell backup с PostgreSQL pair validator, закрывающей все writers и подтверждающей Go. Это требует подготовленного операторского акта/проверенного списка конкретных действий, а не обязательной разработки нового продукта. Независимое storage/key custody, окно/RPO/RTO, текущая source inventory и выбранные rollback volumes также остаются реальными operator inputs. Их нельзя заменить синтетическим PASS.

## 5. Откат и завершение

До возобновления production writes откат имеет проверяемую точку: остановить новый app/issuer/jobs, сохранить обновлённые тома для диагностики, восстановить **предрелизную согласованную** пару в отдельные пустые MySQL9.0 и PostgreSQL17.10 volumes, вернуть точный старый Keycloak26.2.5 и старое приложение/env/keys. Проверить hashes, schema287, бизнес- и delivery identity, существующую аутентификацию и crypto state; выполнить session recovery policy до public access. Исходные/обновлённые тома сохраняются. Никакого старого MySQL на upgraded data directory и никакого старого Keycloak на уже мигрировавшей схеме.

После открытия записи восстановление предрелизного snapshot исключит новые записи и не отменит внешние отправки/платежи. Владелец явно выбирает forward fix либо recovery point и reconciliation по immutable операциям. Нельзя обещать lossless rollback или повторять UNKNOWN автоматически.

Shell `restore-clean` существует, требует точную confirmation string, проверяет остановку writers/self-heal и пересоздаёт schema в указанном работающем MySQL container. Это **не** engine downgrade, не выделение свежего rollback volume и не восстановление PostgreSQL/Keycloak. Генерируемый deploy `ROLLBACK.txt` — scaffold для отдельного review; исполнять его вслепую после этого DB transition нельзя. [restore-prod-db-local.ps1](../infrastructure/scripts/local/restore-prod-db-local.ps1) предназначен для локального prod-like стенда и не является VPS rollback command.

Старое приложение на VPS не имеет RepoDigest; сохранённая read-only image copy привязана к config `sha256:dd0585b6b55e4159a927683386b557fa38c3bd590c1c262694a52d94818a00ba` и всем исходным RootFS layers. Наличие локального тега не заменяет независимо доступный archive с этой identity. Аналогично сохранить старые web/worker/gateway artifacts, APK/signing и закрытый env/secret set. Production backup нельзя считать сохранённым только потому, что deploy оставил `.deploy-backups/<tag>`: скрипт имеет ротацию старых каталогов, а потеря VPS уничтожит такую копию.

В итоговом акте записываются UTC/местное время, оператор/проверяющий, commit и CI, исходные/целевые config IDs и volume identities, backup versions/hashes и измеренный restore time, checkpoints G0–G4, решения по событиям/автоматизации, выполненный rollback либо его контрольная точка. `OTZIV_DEPLOY_COMPLETE` доказывает завершение штатного deploy handoff, но не заменяет этот акт coordinated DB transition. Пока production checks не выполнены, поле `vpsCutoverExecuted` остаётся false.
