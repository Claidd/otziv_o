# Инвентарь патчей и проверок CI

Технический снимок на 2 октября 2026 года, исходная версия `189b28d5`.
Это опись причин, обязательных проверок и условий удаления, а не новый аудит
всех бизнес-сценариев. Размер файла, число строк CI и число коммитов не измеряют
затраченное человеком время.

## Как определить действующую версию

Источником активных digest и связанных доказательств служит
[`reviewed-image-activations.json`](../infrastructure/runtime-security/reviewed-image-activations.json).
Его `manifest.path` выбирает конкретный `reviewed-images-c*.json`, а тот — recipe
и закреплённый parent image. Исходный `reviewed-images.json` является
историческим основанием цепочки и не заменяет этот выбор.

Сохранённые publication, anonymous-download и compatibility receipts относятся
к своим commit/run/attempt и digest. Они не подтверждают безопасность на любую
будущую дату: свежие advisory scans остаются обязательными. Старые recipes и
proofs нельзя переписывать при следующем обновлении. SBOM/provenance Buildx и
GitHub Artifact Attestations — разные механизмы; здесь используется проверяемая
цепочка OCI attestations Buildx.

## Производные образы

Пути `builds/...` в таблице относительны к `infrastructure/runtime-security/`.
Для каждой строки также обязательны publication scan реального registry digest,
проверка OCI provenance/SBOM и отдельная проверка анонимного скачивания. В столбце
«Сопровождение» перечислена работа, а не оценка человеко-часов.

| Компонент и текущий recipe | Причина | Сопровождение и существующие проверки | Проверяемое условие удаления производного recipe |
| --- | --- | --- | --- |
| Keycloak C26, `builds/c26-keycloak/Dockerfile`, цепочка C7/C14/C15/C19/C22/C23/C24 | Provider отзыва сессий; исправления augmentation/migration; сборка CLI; обновлённые Jackson/Parsson/MSSQL/Netty/BouncyCastle/FreeMarker, Java/OS/OpenSSL. C26 обновляет core 2.21.7 в server и shaded CLI, C24 — databind 2.21.7. | Проверять Quarkus classpath, vendor JAR hashes, multi-release classes/service descriptors и неизменность остальных CLI entries/provider. `jackson-core-refresh-inspection.py`, dependency policy, startup/provider proofs, paired migration/restore/replay. | Официальный candidate с сохранённым provider проходит start/start-dev, login/PKCE/refresh/offline/revoke и paired migration/rollback/restore. Каждый прежний source/JAR patch закрывается своей проверкой. Provider является функцией приложения и не удаляется вместе с workaround. |
| PostgreSQL C23, `builds/c23-postgres/Dockerfile`, наследуемые C14/C16 fixes | Engine/runtime closure, gosu, libxml/libxslt/gzip/PCRE2/OpenSSL при сохранении locale, entrypoint и совместимости данных. | Состав extensions/packages, continuity, table/row hashes, coupled Keycloak acceptance и `database_image_guard.py`. | Официальный образ совместимой PostgreSQL 17 ветки проходит те же locale/data checks и откат на отдельной восстановленной копии. Замена образа на существующем томе сама по себе не является проверкой. |
| MySQL C22, `builds/c22-mysql/Dockerfile` и C21 parent | Vendor OS/libxml2/curl refresh с сохранением mysqld. | SHA mysqld, RPM inventory, upgrade/rollback guards и C22 rehearsal. | Исправленный официальный vendor image проходит текущие engine/continuity/restore checks. |
| Grafana C15, `builds/Grafana.Dockerfile` | Go backend rebuild с grpc/thrift/transitives, SQLite/OpenSSL при сохранении frontend/plugins. | Build flags, source hashes, Go build-info, точные binary adjudications, dashboard/data-source/query/auth fixtures. | Официальный image содержит исправления и проходит те же runtime/plugin/data fixtures без исключений для другого binary. |
| Loki C15, `builds/Loki.Dockerfile` | Go/grpc rebuild и HTTP readiness helper. | Build-info, configuration, readiness, query/persistence и мониторинговый runtime fixture. | Официальный image с исправлениями сохраняет эти интерфейсы и проходит fixture. |
| Prometheus C15, `builds/Prometheus.Dockerfile` | Go/grpc rebuild с сохранением web assets и promtool. | Build flags, promtool/config/query/persistence. | Исправленный официальный image проходит все существующие проверки. |
| Tempo C15, `builds/Tempo.Dockerfile` | Go/grpc/thrift/xcrypto refresh и queue shutdown patch. | Source patch regression, dependency graph, drain/readiness/trace fixture. | Upstream содержит исправление drain и исправленные зависимости; shutdown/trace checks проходят. |
| Alloy C23, `builds/c23-alloy/Dockerfile` и `builds/Alloy.Dockerfile` | Go/grpc rebuild с vendor build tags/UI и OpenSSL refresh. | Config validation, Docker observer consumer, ingestion/runtime fixture и C23 acceptance. | Официальный image сохраняет Docker/log/metrics ingestion и проходит те же consumer constraints. |
| Nginx C26, `builds/c26-nginx/Dockerfile` | C22 libexpat refresh и C26 pcre2 10.49-r0; nginx executable сохраняется. | Точное исключение только обновляемого пакета; before/after application/config inventory, включая добавления, symlinks, mode/uid/gid; nginx и HTTP regex/PCRE smoke. | Официальный patched image проходит configuration/TLS/static-delivery checks. Compose proxy и frontend base image проверяются отдельно. |
| Node C23, `builds/c23-node/Dockerfile` | Vendor base и исправления npm graph, включая brace-expansion/undici. | `patch-npm` assertions, npm version/inventory, locked installs и consumers. | Исправленный официальный Node/npm graph проходит установки и тесты всех потребителей. |
| phpMyAdmin C26, `builds/c26-phpmyadmin/Dockerfile` и C22 parent | Alpine PHP83/Apache, сохранённый Composer tree и GNU iconv; C26 pcre2 10.49-r0. | Package locks; полный application/config inventory; PHP version/modules, iconv ABI/conversion tests, Apache configtest, HTTP login/static asset и PCRE smoke. | Официальный image сохраняет PHP/iconv/upload/session/DB behavior и проходит те же проверки. |
| Certbot C25, `builds/c25-certbot/Dockerfile` | Замена точного upstream urllib3 wheel при сохранении runtime. | Wheel integrity/import/version/plugins и scan. | Официальный image содержит исправление и проходит renewal/plugin/config fixtures; smoke не должен незаметно выполнять настоящий renewal. |
| mc C22, `builds/c22-mc/Dockerfile` | Alpine runtime refresh с сохранением исправленного Go client. | Binary SHA, source/license evidence, local S3 initialization/versioned-object/auth fixtures. | Поддерживаемый официальный client с исправлениями проходит те же S3 semantics. |
| Local S3, service identity `minio`, `builds/versity-c14/Dockerfile` | В local/test окружении используется VersityGW 1.8.0 с OpenSSL refresh. | Runtime APK lock, binary SHA, versioned S3 restore/restart fixtures. | Исправленный официальный Versity image проходит те же fixtures. При смене реализации нужен отдельный том: это не доказательство совместимости MinIO on-disk data. |

Для C26 publication runs: Keycloak `36953214402`, Nginx `36953242831`,
phpMyAdmin `36953263889`; исходный commit
`c81a29c1eda2ba157702560338467521cd3e484e`. Они подтверждают свои опубликованные
образы. Совместимость и активация подтверждаются отдельно соответствующими
receipts в `proofs/c26-*`, а не одним успешным push.

83 исходных файла C26 опубликованы отдельным
[архивом GitHub Release](https://github.com/Claidd/otziv_o/releases/tag/runtime-evidence-c26-20261002).
Размер ZIP — 3 131 555 байт, SHA-256 —
`2047d0d34ed000da168820449de9190c70ca9e782a9cc532863635253e6aab2b`.
В Git остаются манифест и загрузчик. Перед offline validators CI восстанавливает
эти точные файлы в ignored `proofs/c26-*` и проверяет их хеши; перенос не
отменяет проверки receipts и не делает архив доказательством для других образов.

## Maven build-support

Состав reactor и audit scope описаны в
[`backend/build-support/README.md`](../backend/build-support/README.md).

| Компонент | Причина и сопровождение | Условие сокращения |
| --- | --- | --- |
| Dependency audit adapter | Сохраняет полный эффективный plugin/report/extension graph, включая транзитивные зависимости. Требует сверки с upstream ODC, Maven model/realm behavior и fail-closed regression tests. | Upstream реализует необходимые coverage/error semantics; реальные application/bootstrap/issuer reports показывают тот же охват. |
| Testcontainers transport | Локальная совместимость transport/dependency graph тестового окружения. Требует bootstrap installation, audit собственного POM и реальных container/DB tests. | Поддерживаемая upstream комбинация проходит те же tests с полным dependency scan. Удаление Site не должно исключить этот модуль из аудита. |
| Site derivative, удалён | Вместо прежнего форка Java audit adapter доказывает, что единственный исключаемый build root — неиспользуемый штатный Site 3.12.1 из Maven 3.9.15. Python effective-POM preflight только отмечает кандидата. | Сопровождать проверки origin/default bindings, всех исходных деклараций и полных execution plans/forks. При неизвестном происхождении, версии Maven, вызове или ошибке планирования полный аудит остаётся включённым. Site, достигнутый через другой plugin graph, продолжает сканироваться. |

В Maven 3.9.15 Site 3.12.1 попадает в effective model из default lifecycle
bindings в `maven-core/META-INF/plexus/components.xml`, а не из объявления Site
в super POM. Поэтому удаление одного pin, `maven.site.skip` или `phase=none`
не является доказательством безопасного удаления. Нельзя заменять эту задачу
общим исключением `maven-site-plugin` из сканирования. Реализованная политика
сохраняет полный аудит при неизвестном происхождении или изменившемся lifecycle,
проверяет фактический execution plan и учитывает явные plugins, pluginManagement,
reporting, extensions, parents и profiles. Произвольная ручная команда не
объявляется поддерживаемой production build-командой автоматически.

## Проверки CI

Основные definitions: [quality-gates](../.github/workflows/quality-gates.yml),
[dependency-audit](../.github/workflows/dependency-audit.yml),
[secret-scan](../.github/workflows/secret-scan.yml),
[sql-injection-guard](../.github/workflows/sql-injection-guard.yml).

| Job/группа | Что проверяет | Допустимое сокращение |
| --- | --- | --- |
| Changes / dependency change detection | Затронутые модули и корректные base/head ranges. | Уточнять mapping при conservative fallback на неизвестные пути; regression tests сохраняются. |
| Repository contracts | Workflow/source lineage, hygiene, migration immutability, release sessions, deploy/DB/restore guards и API contracts. | Удалять контракт вместе с удалённой функцией; не снимать весь job из-за большого числа проверок. |
| Backend reuse | Соответствие повторно используемых результатов source tree, runner/JDK, run/attempt. | Только равноценная provenance/freshness проверка с fail-closed fallback. |
| Backend tests, три shards | Business/security behavior, реальные SQL/Testcontainers tests, budgets и packaged runtime scan. | Балансировать по JUnit timings, сохраняя каждый класс. |
| Backend aggregate | Полнота shard coverage, отсутствие пропусков и дублирования. | Другая явная полная coverage проверка; зелёный отдельный shard не заменяет aggregate. |
| Issuer security | Provider/provisioning, login/revoke, startup, DB transition и paired recovery. | Переиспользовать только точные проверенные inputs; переход на official Keycloak не отменяет эти функции. |
| Frontend / mobile | Locked install, dependency audit, unit tests и production builds. | Сравнить реально одинаковые graph/trigger/severity прежде, чем убирать повторный audit. |
| Browser smoke | Реальное web/mobile UI: login/payment/order и совместимость с backend. | Unit tests сами по себе не заменяют browser behavior. |
| Android compile | Native plugins, tests/lint, asset sync и debug assembly. | Оптимизировать SDK/cache; signing и проверка release APK остаются отдельными операциями. |
| WhatsApp / external-review worker | Lifecycle, authentication/readiness, dependency audit и runtime sandbox. | Сохранять при неизменной функции; патчи других компонентов не дают основания удалить эти checks. |
| Client parity | Shared client/backend/mobile contracts. | Заменить явной равноценной проверкой границы совместимости. |
| Upstream inventory/images/aggregate | Полнота матрицы и scans закреплённых Compose default images. | Уменьшать повторные загрузки, сохраняя свежесть scans и coverage всех images. |
| Integration images | Реальные Dockerfiles, sandbox/trust/readiness, built-image scan и immutable OCI artifact. | Повторно использовать неизменившиеся образы по существующим input/provenance guards. |
| Monitoring proof runner / candidates | Общий fixture runner и реальные configuration/query/ingestion/persistence/shutdown tests. | Использовать общий runner и пропускать только доказанно неизменившийся build. |
| Release manifest | Привязка всех images/reports/reuse к точным commit/run/attempt. | Сохранить как обязательную проверку перед доставкой CI-образов. |
| Reviewed publication / anonymous download | Ручная публикация кандидатов, OCI chain, actual digest scan и свежий внешний pull. | Это отдельный поток, не обязательная полная пересборка каждой обычной поставки. |
| npm dependency audit | Locked full/production dependency graphs и заданные severity thresholds. | Сокращать дубли только после сравнения реальных graph, thresholds и triggers. Автообновления не заменяют audit. |
| Maven NVD + build-support audit + OSV | Application/test/provided/runtime/system/plugin graphs, bootstrap и standalone issuer; разные advisory sources. | Сохранять все stages и failure propagation. Missing/stale/malformed reports, unknown severity и API errors не могут давать PASS. |
| Secret scan | Запрещённые tracked files, текущий tree и новые commits; отдельный исторический report. | Оптимизировать историю/cache, сохраняя точный checked Git range. |
| SQL injection guard | Опасная сборка SQL и regression tests самого анализатора. | Удалять только вместе с равноценной проверкой; это не замена полному SAST. |
| Scheduled production-image scans | Новые advisories для уже выпущенных immutable images без source изменений. | Не заменять единственным pre-release scan. |

## Измерение стоимости сопровождения

Для elapsed/queue time и critical path уже существует
[`release_metrics.py`](../infrastructure/scripts/prod/release_metrics.py):

```powershell
python -B infrastructure/scripts/prod/release_metrics.py --run-id <run-id> --output <ignored-output.json>
```

Для rebuild брать job/step timings отдельного publication run из activation
record. Для повседневного CI сравнивать одинаковые типы запуска и разделять cold
cache, warm cache и reuse. Хранить commit, run/attempt, runner/JDK, выполненные
jobs, queue time, wall time и сумму runner time отдельно: параллельные jobs
нельзя просто сложить для оценки времени ожидания выпуска.

Например, в историческом main run `36862870691` backend shards заняли
594/461/418 секунд, issuer security — 375, backend image — 349, repository
contracts — 167. Это один наблюдаемый запуск, не норматив и не прогноз.
Первый кандидат на оптимизацию — измеренное узкое место: балансировка shards,
cache misses или повторная работа с теми же inputs. Новое сокращение принимается
после сравнения runs и подтверждения неизменного охвата.

Для человеческого сопровождения у нового workaround следует записывать причину
или воспроизведение, owner/component, upstream issue/fix, проверку срабатывания,
проверку отсутствия побочных изменений и условие удаления. Человеко-часы сейчас
не измерены; числовая оценка этой части не приводится.

## Порядок снятия workaround

1. Выбрать один компонент и конкретное upstream исправление.
2. Собрать отдельного кандидата, сохраняя прежний accepted parent и receipts.
3. Выполнить свежий scan и все перечисленные compatibility checks на точном
   candidate digest; для databases — paired backup/restore/rollback.
4. Проверить publication/provenance и доставку тем же путём, который использует
   выпуск; затем изменить default activation.
5. Удалить только ставший ненужным текущий workaround и его текущие проверки.
   Исторические доказательства остаются неизменны. Согласовать общий выпуск через
   [единый main workflow](UNIFIED_MAIN_WORKFLOW.md).

## Автоматические предложения обновлений

Конфигурация `renovate.json` ограничивает обычные предложения тремя PR,
предложения исправлений уязвимостей — двумя, отключает automerge и создаёт
черновики для проверки. Angular обновляется согласованной группой; переходы
на новую major-ветку требуют отдельного решения. Закреплённые производные
образы и локальные Maven adapters не обновляются ботом вслепую: их замена
должна пройти перечисленные выше доказательства совместимости.

На GitHub включены vulnerability alerts; автоматическое создание исправлений
Dependabot оставлено выключенным, чтобы два бота не предлагали параллельные
изменения одного graph. Установка Renovate GitHub App и разрешение репозитория
проверяются отдельно от наличия JSON в Git. Ни alerts, ни PR бота не заменяют
существующие NVD/OSV/npm/image scans и тесты. Публикация обновления проходит
обычный единый выпуск; автоматическое принятие зависимостей не включается.

Ротация ранее опубликованных секретов выполняется отдельной операцией по
решению владельца. Сокращение патчей, уборка файлов и установка исправленных
образов не подтверждают отзыв прежних учётных данных.
