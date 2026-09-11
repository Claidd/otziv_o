# P00/P06: наблюдаемый production-флот и определения расписаний

Реестр основан на read-only наблюдении одного авторизованного VPS **2026-09-07 14:06:28 UTC**, а не на Compose-файле или предполагаемом deployment. Хост не изменялся. Неизвестные дополнительные хосты не объявляются отсутствующими.

[Fleet registry](FLEET_REGISTRY_2026-09-07.json) содержит все 13 контейнеров, их фактические image configuration ID, доступные RepoDigests, restart policy, health/start time и разрешённые scalar env flags. Источник — `.codex-tmp/finalization-20260907/fleet-inventory.json`, хеш сохранён в реестре. Адрес VPS не скопирован в tracked registry, секреты/содержимое бизнес-данных не включены.

- На этом хосте наблюдались один backend, один web/nginx, два WhatsApp instance одного образа, MySQL, Keycloak/PostgreSQL, Prometheus, Grafana, Alloy, Dozzle, Loki и Tempo: **13 контейнеров / 12 различных configuration ID**.
- External-review-worker, docker-observer, signals publisher и MinIO на этом хосте не наблюдались. Их наличие в candidate Compose или локальных smoke не означает production deployment. Внешнее S3 может существовать отдельно; отсутствие MinIO-контейнера не доказывает отсутствие объектного хранилища.
- Production schema — **V1.10.287**. В момент наблюдения `lead_sync_queue=0`, `review_performer_assignments=0`. Нулевые очереди — состояние снимка, не доказательство выключенных producers/consumers.
- Whitelist показывает `OTZIV_TASK_SCHEDULING_POOL_SIZE=4`. Это конфигурационный input опубликованного `spring.task.scheduling.pool.size`, не измерение фактических активных потоков.
- Все 13 контейнеров были running/healthy в момент наблюдения. Docker health не подтверждает каждый provider, scheduler, backup или alert recipient.

## Расписания извлечены из опубликованного JAR

[Scheduler definitions](PUBLISHED_SCHEDULERS_2026-09-07.json): **68 методов с `@Scheduled` в 58 классах**, плюс один `SchedulingConfigurer` — `BackupScheduler`. Не загружались и не инициализировались application classes: extractor читает classfile через ASM и сериализует только метаданные.

Проверен точный JAR опубликованного `fc191ac23cfe600bac3c8b1741970e8cb22143e0`:

`031ca4a794cfdb40718fdcdabf2c5ed44fbffe814efdb3d6534f59b56284be1b`

Все **2702 извлечённых class entries** побайтно сверены с ZIP entries этого JAR. Для каждого scheduler сохранены class SHA-256, signature, аннотация с фактическими cron/delay expressions, class/method conditions, hash определения и source path/hash на точном git revision. Общий hash набора определений:

`9dbe6286c3ee195e30fea20d36d219beabf55e89df535eb388a20c5198d6ddd8`

Методы покрывают finance/common-billing/contractor payments, client messages, manager workflows/reports, worker activity, workload shadow, reviews, performers, legacy lead sync, интеграции и housekeeping. Полный список имён/expressions находится в JSON, поэтому комментарии, устаревшие source snapshots и закомментированный Java-код не увеличивают счётчик задач.

Ограниченный обход статических call/field references сопоставляет ссылки на flags с наблюдаемыми env и `app_settings`. `maxCallDepth`, признаки обрыва и неизвестные вызовы записаны явно. Это **ссылки на конфигурацию, не вычисление всех условий исполнения**. Неизвестные cron/time/zone overrides не заменяются default-значениями из аннотаций. Локальные, условные, программно добавленные или динамически изменённые задачи требуют runtime inventory.

Read-only GET `/actuator/scheduledtasks` на production вернул **401** в **14:58:25 UTC**: `.codex-tmp/finalization-20260907/scheduler-runtime-observation.json`, SHA-256 и поля наблюдения включены в scheduler registry. Аутентифицированный повтор и обход доступа не выполнялись. Поэтому **registered scheduler count, фактические trigger times, успешность/частота исполнений остаются неизвестными**. Статические 68 методов нельзя называть 68 зарегистрированными либо работающими задачами.

## Существенные условия именно опубликованной версии

| Контур | Наблюдение и статический контракт | Корректная интерпретация |
|---|---|---|
| Архивация заказов | Env apply/schedule=false; DB `archive.orders.apply.enabled`, `archive.orders.schedule.enabled`, `archive.orders.schedule.worker.enabled` — true | `OrderArchiveDryRunService.runtimeSettings()` использует DB overrides, перекрывающие env defaults. **Нельзя писать «архивация выключена»**. Run mode/time/claim state в inventory не прочитаны |
| Lead sync | `LEAD_SYNC_OUTBOUND_ENABLED=false`, legacy queue0 | Убирает conditional `LeadSyncServiceImpl`; отдельный `VpsSyncService.retryFailedSync()` имеет собственный `@Scheduled` и не проверяет этот флаг. Нулевая очередь не превращает его в отключённый consumer. Новый V298 специально запрещает старую схему consumer после rename |
| Integration outbox | `OTZIV_INTEGRATION_OUTBOX_RELAY_ENABLED=false` | Опубликованный scheduler имеет `@ConditionalOnProperty(...relay-enabled=true)`. Наблюдаемое значение против включения; фактическая bean registration не считана |
| Analytics rebuild | `OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED=true` | Условие scheduler с `havingValue=true` имеет включающий input. Не доказательство выполненного rebuild |
| MAX polling | `MAX_BOT_LONG_POLLING_ENABLED=false`; webhook auto-register=true | Long-polling conditional input выключен, webhook-контур имеет отдельные настройки. Нельзя считать весь MAX выключенным |
| External review checks | Env `EXTERNAL_REVIEW_CHECK_ENABLED=false`, worker-контейнер не наблюдался | Статический `ExternalReviewCheckRuntimeSwitch` требует hard master и fresh DB flag; property default=false. Effective binding/DB switch/registered task не считаны runtime API; отсутствие worker не является доказательством здоровой readiness |
| Performer assignments | DB `performers.rollout.enabled=false`, assignments0 | Наблюдаемые rollout/input и occupancy выключены/пусты; scheduler-метод существует в JAR. Их наличие не доказывает dispatch |
| Client messaging | DB `client.messages.live.enabled=true`, `client.messages.worker.enabled=true`; Telegram sending=true | Нельзя распространять безопасные local-smoke defaults на production. Реальные вызовы в этой инвентаризации не отправлялись |
| WhatsApp health | Env health-monitor=true, restart=false; два gateway healthy | Вызов `properties.getHealthMonitor().isEnabled()` и отдельный restart flag видны в точном source. Live provider session и успешность мониторинговых уведомлений не проверялись |
| Backup | backup enabled/schedule/catch-up=true | `BackupScheduler.configureTasks()` условно добавляет cron daily и fixed-delay catch-up, использует scheduler lease. Это дополнительный динамический scheduler, которого нет в счётчике68 |

Backup S3 independence/privacy/encryption/object-lock flags — заявления конфигурации. Они не подтверждают физическую независимость storage, successful backup/restore или его RPO/RTO. Наблюдавшиеся systemd timers включены в registry; `dpkg-db-backup.timer` не выдается за backup бизнес-БД. Фактическая backup evidence/recovery проверяется отдельным пакетом P07.

## Точное покрытие vulnerability scans

[Сопоставление deployed images с текущими pins](DEPLOYED_AND_REPOSITORY_IMAGES_2026-09-07.md) отделяет подготовленные исправления application images от ещё не выполненных обновлений MySQL и других upstream images. Их прежние digest остаются в активных Compose: это незавершённая конфигурация P06, а не только ожидание deployment.

Production configuration ID нельзя сравнивать только с Docker Desktop `.Id`: containerd-backed Docker показывает OCI index, а VPS Docker — config digest. Для каждого используемого локального образа проверяется **Config, выбранный `docker image save` manifest, и SHA-256 его bytes**. После скана `report.Metadata.ImageID` должен точно совпасть с наблюдаемым production configuration ID. Tag или сходный состав зависимостей не заменяет это сравнение.

Недостающий образ допускается получить только read-only командой `sudo -n docker image save <observed immutable ID>` на авторизованном VPS с binary-safe stream в локальный `docker image load`. Архивы на VPS не записываются, контейнеры там не создаются и не запускаются. Локально исполняется только pinned scanner; source image не запускается.

Серийный runner использует неизменённый `scan.mjs`: один собственный scanner за раз, limit2GB, полный raw report + SBOM + triage + diagnostic. Parent разрешил ограниченное временное пересечение со своим отдельным issuer scanner. Полный scan state и identity proofs — `.codex-tmp/finalization-20260907/fleet-scans/fleet-scan-state.json`. Registry хранит актуальный для последней генерации статус **по каждому из 13 контейнеров**.

Все **12 различных образов / 13 контейнеров** проверены. Независимая сверка report/config identity, raw/triage/SBOM/log hashes и итоговых счётчиков прошла **15:24:21 UTC**: `.codex-tmp/finalization-20260907/fleet-artifact-verification.json`.

| Образ / контейнеры | HIGH/CRITICAL rows | С версией исправления | Без версии исправления |
|---|---:|---:|---:|
| Web/nginx | 34 | 34 | 0 |
| Backend | 10 | 10 | 0 |
| WhatsApp — один образ, два instance | 131 | 28 | 103 |
| MySQL | 165 | 165 | 0 |
| Prometheus | 100 | 96 | 4 |
| Grafana | 127 | 127 | 0 |
| Alloy | 46 | 43 | 3 |
| Dozzle | 11 | 11 | 0 |
| Loki | 55 | 55 | 0 |
| Keycloak | 72 | 71 | 1 |
| Tempo | 78 | 78 | 0 |
| PostgreSQL | 172 | 86 | 86 |
| **Итого по различным образам** | **1001** | **804** | **197** |

Это **347 различных advisory ID**, представленных 1001 строкой «образ / пакет / уязвимость», а не 1001 уникальная CVE. Общий образ WhatsApp учитывается один раз. Каждый из 12 старых production-образов имеет findings с доступными исправлениями и не проходит нынешний available-fix gate. Это не ошибка исполнения scanner: полный scan/report успешно получен.

[Full-fleet CVE review](FLEET_CVE_REVIEW_2026-09-07.json) сохраняет все HIGH/CRITICAL rows, installed/fixed versions, source class/type, ссылки advisory, exact image IDs и report hashes. Reviewer roles — предложения для распределения работы; назначенный человек, фактический review, risk acceptance и expiry отсутствуют. Нижние severity сохранены в полных raw reports и их счётчиках registry.

Это результаты старых развёрнутых образов. Исправленные candidate image reports не заменяют их findings. Скан нового MySQL-кандидата и реальная upgrade rehearsal сохраняются отдельно, без изменения production. Проверка полного наблюдаемого флота не означает покрытие не наблюдавшихся MinIO/phpMyAdmin/certbot либо неизвестных дополнительных хостов.

Автоматический available-fix gate и статус review сохранены. Никаких scanner exceptions, риск-acceptance, ответственных сотрудников или expiry approvals эта инвентаризация не назначает. Полное покрытие образов также не означает доказанную reachability либо одобренный production rollout.
