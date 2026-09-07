# Выпуск архитектурных изменений

Состояние кода и результаты проверок фиксируются отдельно от production activation.
Штатный локальный запуск: `infrastructure/scripts/local/prod-like-smoke.ps1`.
Он получает свежую копию БД с VPS, восстанавливает отдельный local volume и
применяет предусмотренную скриптом санитарную обработку/отключение внешних отправок.
Не включать `AllowLocalMessengerSending` при тестировании реальных данных.

## Лиды: обязательная остановка старого потребителя

V293 добавляет frozen payload/version, command ID, delivery state, lease/token
и аудит решений. V298 переименовывает таблицу в `lead_command_queue`.
Это намеренный барьер: старый binary не может читать UNKNOWN/QUARANTINED как
старую retry queue и повторно отправлять их. Миграции нельзя применять при
работающем старом backend/scheduler. Нужен drain всех старых экземпляров.

1. Остановить admission/старые schedulers; дождаться активных отправок.
2. Сделать согласованную копию, сохранить конфигурацию и версии images.
3. Применить миграции с `LEAD_COMMANDS_DISPATCH_ENABLED=false`.
4. ADMIN/OWNER: `POST /api/admin/lead-commands/classify-legacy?dryRun=true&limit=100`.
   Затем теми же bounded batches `dryRun=false`; операция CAS и повторяема.
   Payload/телефоны/токены в отчёт не попадают.
5. Валидная старая команда становится UNKNOWN (либо DEAD при исчерпании попыток),
   испорченная — QUARANTINED. История не удаляется. Восстановление payload только
   из текущего состояния лида не доказывает исходную команду.
6. Решение `POST /api/admin/lead-commands/{id}/resolve` требует resolution и reason:
   CONFIRMED_DELIVERED, CONFIRMED_NOT_DELIVERED, SUPERSEDED. Для QUARANTINED
   доступно только SUPERSEDED. UNKNOWN нельзя считать неотправленным по timeout.
7. После сверки включить dispatch. Idempotency-Key постоянен; retry не меняет
   payload/version. Принимающая сторона пока не доказала persistent dedup,
   поэтому 5xx/timeout/expired lease переходят в UNKNOWN без автоматического replay.
   Явное 429 допускает backoff; неподтверждённая ранняя команда блокирует следующие
   команды того же лида, сохраняя порядок, но не всю очередь.

Lease 120s, connect 5s/read 30s, claim/completion транзакции короткие и fenced.
Откат на старый binary после V298 заблокирован. Предпочтителен roll-forward.
Обратное переименование без преобразования состояний запрещено: оно вернуло бы
опасного старого consumer. Отдельный rollback требует drain и проверенного
совместимого binary/согласованного restore, а не удаления Flyway history.

V299 добавляет generated blocking scope и индексы для FIFO claim и health.
READY/PROCESSING/UNKNOWN/LEGACY/QUARANTINED/DEAD блокируют следующий command
того же лида; только подтверждённое разрешение снимает барьер. Generated column
может потребовать перестроения таблицы: включить миграцию в окно drain, заранее
проверить свободное место и длительность на репрезентативной копии. Индекс не
меняет правила replay. На изолированной fixture со 100k исторических команд
claim сократился с 76,3 до 0,076 ms; это не оценка production throughput.

## Исполнители

V288 сохраняет OFFER/READY/ACCEPTED intents. V289 запрещает два активных OFFERED
для одного assignment на уровне MySQL. Перед V289 проверить существующие
дубликаты; не удалять их автоматически. Выпуск с
`PERFORMERS_NOTIFICATIONS_DISPATCH_ENABLED=false`, затем сверка исторических
LEGACY_UNKNOWN и включение потребителя. Старые schedulers должны быть остановлены.
TTL предложения начинается от подтверждённой доставки, не от создания intent.
Telegram timeout не запускает слепой повтор; исход UNKNOWN требует решения.
ADMIN/OWNER имеет `/api/admin/performers/notifications` и documented resolve API.

V300 добавляет индекс готовности и точный marker поколения READY intent.
Backfill учитывает только существующий intent текущего поколения, включая UNKNOWN:
неопределённый исход не становится новой отправкой. Producer сначала flush-ит
изменение generation, затем фиксирует intent и marker в той же транзакции.
Полный порядок и rollback находятся в `PERFORMER_NOTIFICATION_ROLLOUT.md`.
Если после миграции временно запускался старый producer, перед возвратом нового
нужны drain и повторная сверка/backfill marker; один restart не восстанавливает
согласованность этого оптимизированного индекса.

## Сессии

Следовать `AUTH_EPOCH_PUSH_REVOKE_ROLLOUT.md`: migration V294, наблюдение shadow,
drain старых writers, scoped bootstrap, dispatch и только затем strict enforcement.
Environment defaults: mode=off, dispatch=false, cutover-confirmed=false.
Эти defaults не объявляют завершённый protocol активным в production.
Роли/активность и прежние auth_epoch checks продолжают действовать.
Offline sessions и pending password ambiguity нельзя обходить массовым logout
или повторной записью пароля без явного решения и аудита.

## WhatsApp и browser worker

Порядок: gateway с durable ledger → backend producer со стабильными ID.
Ledger сохраняется в существующем private `/auth/outbound-operations`; его нельзя
очищать при рестарте/откате. Полный protocol: `whatsapp/OUTBOUND_OPERATIONS.md`.
Очереди используют постоянный ID из state/delivery token; same-ID/different-payload
даёт conflict и требует сверки. Администратор может прочитать результат без
повторной отправки через `/api/admin/whatsapp-operations/{clientId}/{operationId}`.
NOT_FOUND после потери/restore ledger не доказывает неотправку.

Gateway stop grace 330s, worker 400s (больше допустимых drain deadlines),
backend 120s с graceful shutdown. Зависший send сохраняет UNKNOWN при рестарте.
Применяется `infrastructure/runtime-security/chromium-seccomp.json` на Docker host
для Chromium sandbox. Read-only FS, non-root, cap-drop ALL и no-new-privileges
сохраняются. Проверка реального образа должна пройти с этими параметрами.

## Docker monitoring

Только docker-observer получает Docker socket; Dozzle и Alloy используют его
по отдельной internal network. Observer не публикует порт на host и разрешает
только необходимые read endpoints. Нельзя заменять фильтр unrestricted socket
proxy при несовместимости клиента. Сборка/профиль входят в deploy bundle.
Запускать `infrastructure/docker-observer/proxy.test.js` и actual-consumer smoke.

## Recovery и внешние сигналы

`infrastructure/recovery/README.md` и `infrastructure/monitoring/README.md`
описывают подготовленные CLI, конфигурацию, encrypted object verification,
consistency manifest, независимый runner и deadman receipts.
Activation требует выбранного внешнего storage, notification receiver,
утверждённых RPO/RTO и согласованного recovery point всех ресурсов.
Локальный restore MySQL не равен полному DR Keycloak/объектов/секретов/ledger.
End-to-end restore/login и время восстановления проверяются отдельно на
изолированном контуре; mocks и один pg_restore не закрывают этот пункт.
