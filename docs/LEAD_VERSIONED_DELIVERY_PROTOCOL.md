# Версионированная синхронизация лидов — protocol 1

P03/P14 передают **полный снимок текущего состояния** источника, а не неизменяемое
историческое событие каждого редактирования. Snapshot фиксируется в текущей
business transaction после flush и canonical Lead lock. `LeadCommandWorker` —
единственный HTTP sender; ручной `/api/leads/sendToServer`, outreach bridge и
BEFORE_COMMIT listener только записывают команды.

## Идентичность и порядок

V306 создаёт единственный `lead_command_source.source_id` на базу-источник.
UUID хранится в backup и сохраняется при восстановлении. `lead_command_stream`
выдаёт возрастающий `entityVersion` для lead ID: **Lead → stream lock → snapshot /
queue INSERT → outer commit**. Более поздний producer не может получить версию
или committed queue row, пока предыдущий producer того же Lead остаётся hidden
в незавершённой транзакции. Rollback откатывает и sequence, и queue row.

JSON DTO содержит необязательное поле `command`: protocolVersion, sourceId,
entityId, entityVersion, operationId, kind (SYNC/UPDATE/IMPORT). Вся metadata
вместе с payload входит в прежний canonical modern JWT checksum. Idempotency-Key
должен совпасть с operationId; сам заголовок без подписанной metadata ничего не
авторизует. JWT jti/время подписи обновляются на транспортную попытку, business ID,
версия и payload остаются прежними. `payload_version` — отдельная версия JSON codec.

SYNC/IMPORT протокола 1 применяют full snapshot, включая очистку nullable полей.
IMPORT разрешает upsert уже связанного исходного Lead вместо создания второго
локального объекта. UPDATE сохраняет строгую семантику: отсутствующий target даёт
404 и не создаётся. У legacy SYNC без command сохраняется прежняя частичная
null-семантика; отсутствие command не добавляет новое null-поле в checksum.

Телефон нормализуется для fence, исходная запись по возможности находится по
существующим вариантам номера. `(sourceId, entityId)` после первой успешной записи
ссылается на receiver Lead ID. Изменение телефона сохраняет ту же сущность и
навсегда оставляет старый telephone fence: поздний legacy запрос на старый номер
не может обойти версию. Удаление связанного target не разрешает молча создать новый.
Новый source/entity не может присвоить уже занятый телефон.

## Atomic receiver и receipt

`LeadCommandReceiver` объединяет в одной MySQL/JPA транзакции:

1. Receipt по `(sourceId, operationId)` и immutable hash; unique source/entity/version.
2. Source entity high-water lock, затем telephone target fence и Lead mutation lock.
3. Business mapping, high-water и terminal receipt. Ошибка/rollback не оставляют
   ни половины business write, ни успешного receipt.

Точный повтор возвращает сохранённый receipt без повторного business write.
Тот же ID с другим envelope либо другая операция с уже занятой версией даёт 409.
Не виденная ранее старая версия возвращает STALE с текущим appliedVersion и не
меняет Lead. Receipt содержит protocol/source/entity/version/operation/kind/hash,
outcome APPLIED или STALE, appliedVersion. Он возвращается только после commit proxy.
Sender признаёт успех только при точном совпадении; legacy 2xx/text или чужой receipt
становятся UNKNOWN. Позднее завершение истёкшего claim не обходит queue token fence.

Timeout/5xx/отсутствие проверяемого receipt остаются UNKNOWN без автоматической
повторной отправки. 429 до admission использует bounded backoff, прочие явные 4xx
останавливают команду. Для решения UNKNOWN оператор сверяет receiver receipt
по source/operation/hash (без чтения PII payload) и применяет существующий audited
`/api/admin/lead-commands/{id}/resolve`. CONFIRMED_DELIVERED требует доказанного
receipt; CONFIRMED_NOT_DELIVERED — доказанного отсутствия применения, не timeout.
UNKNOWN блокирует следующие команды этого Lead, сохраняя прогресс других Lead.

Receipt/entity high-water/telephone fences и manual-request identity не имеют
автоматического TTL удаления. Их backup и retention должны покрывать весь
согласованный retry/rollback horizon; удаление очереди не разрешает удалять receiver
память. Restore более старого receiver state требует reconciliation до возобновления.

## Повтор ручной кнопки

`POST /api/leads/sendToServer?leadId=…` сохраняет прежний body, возвращая также
`X-Lead-Command-Id`. Новый caller передаёт UUID `Idempotency-Key` одной пользовательской
операции; таблица manual requests сохраняет binding lead→command. Повтор ключа
возвращает прежнюю команду, другой Lead с тем же ключом получает 409.

Старый caller без ключа коалесцирует любой неразрешённый IMPORT этого Lead, включая
UNKNOWN/DEAD/QUARANTINED: ID, версия и payload не заменяются изменившимися данными.
После подтверждённого terminal resolution новое нажатие без ключа означает новую
операцию передачи текущего снимка. Receipt/idempotent upsert не создаёт второго Lead.
Это явно ограниченная совместимость, не определение нового бизнес-намерения по времени.

## Совместимый выпуск и activation gates

Эти шаги являются планом выпуска, а не свидетельством выполненной production-активации.

1. Инвентаризировать все источники, backend/receiver replicas, HTTP/Telegram writers,
   outreach bridge, schedulers и накопленные commands. Сохранить source UUID,
   schema/image identities и обе стороны delivery history. Одновременно только
   **одна активная база** с данным source UUID: оригинал и восстановленный clone
   не имеют права параллельно производить команды. Общие backend replicas над
   одной MySQL допустимы; две независимые MySQL — нет. Local restore всегда outbound=false.
2. Drain прежних writers и sender, завершить/сверить in-flight. Применить новую V306;
   прежние Flyway файлы не изменяются. ALTER/index может перестраивать таблицу:
   заранее проверить длительность/место в согласованном maintenance window.
3. Сначала выпустить receiver с dispatch выключенным. `lead.commands.receiver.enabled`
   по умолчанию false. Для true обязателен непустой точный allowlist
   `lead.commands.receiver.allowed-source-ids`; значения берутся из source inventory.
   Версионированный запрос к неактивному receiver получает отказ до записи.
4. Legacy unversioned временно совместим при
   `lead.commands.receiver.legacy-unversioned-enabled=true` (default). После первого
   versioned binding старые запросы на тот target блокируются независимо от флага.
   Обратный GET pull также блокируется для bound target через тот же transaction fence.
   После drain всех прежних producers установить false. Старый receiver не считается
   совместимым для нового producer: обязательны новые receipt/version правила.
5. Сохранённые до V306 команды не получают выдуманных source/version. Новый sender
   не отправляет строку без проверяемой identity/hash; она карантинируется и остаётся
   в аудите. По прежнему runbook решить UNKNOWN/LEGACY/QUARANTINED; только после
   явного SUPERSEDED допускается отдельный текущий snapshot вместо утраченного события.
6. Producer сначала только пишет наблюдаемые queue rows при
   `lead.commands.dispatch-enabled=false`; вторую реальную shadow-отправку не запускать.
   Проверить source/sequence/payload/receiver compatibility на изолированном стенде.
   Для dispatch=true дополнительно обязателен `lead.commands.producer-source-id`,
   в точности равный сохранённому source UUID. Неподтверждённый/клонированный источник
   не должен получать эту отдельную конфигурацию активации.
7. После подтверждённого single-owner cutover и решений по старой истории включить
   dispatch. Наблюдать due/UNKNOWN/fenced/queue-age метрики и reason codes. Любая
   проблема receipt/ordering останавливает источник, а не включает legacy fallback.

Откат: сначала отключить producer/dispatcher и дождаться bounded in-flight;
сохранить receipts, high-water, source UUID, request IDs и очередь. Старый sender,
принимающий любой 2xx либо не понимающий signed metadata, не является допустимым
rollback. Возврат legacy receiver/writers при существующих bindings требует
отдельного контролируемого преобразования/reconciliation, а не удаления V306 tables.

## Локальное доказательство

`LeadVersionedDeliveryMySqlIntegrationTest` использует реальные Spring TX proxies,
JPA Lead/mapper и production JDBC DDL/receipt SQL. Проверяет hidden producer при
незавершённом commit, InnoDB wait, старое после нового, одновременный duplicate,
restart, late rollback, signature/source substitution, immutable payload/version,
legacy cutover и manual UNKNOWN request identity. Реальные внешние HTTP отсутствуют; late receipt использует локальный fake transport.
`LeadCommandWorkerTest` отдельно управляет транспортом: exact/stale receipt,
legacy success, потерянный ответ, чужой receipt и отсутствие activation.
Положительный результат не заменяет проверку целевого receiver/fleet перед выпуском.
