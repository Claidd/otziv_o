# Идентичность клиентских уведомлений и переход старой истории

Изменения подготовлены и проверяются локально. На VPS новая схема отправки не активировалась. Идентификатор означает конкретное бизнес-событие; hash текста, текущего адресата или изменяемого `statusChangedAt` не используется как новое событие.

## Владельцы и фактические производители

| Производитель | Идентичность и повтор |
|---|---|
| `LeadWorkNotificationService` | `lead-work:<leadId>:<persisted generation>`; переход и frozen envelope сохраняются в бизнес-транзакции, доставка после commit вне транзакции. Повтор `TO_WORK` сохраняет generation. Исторический generation0 не отправляется. |
| `SendMessageController` | Persisted manual operation создаётся при открытии новой формы и привязана к actor. Тот же hidden operation ID сохраняется после UNKNOWN; изменение текста/адресата этой формы не заменяет envelope. Новая форма означает отдельное явное действие. |
| `GroupReplyServiceImpl` | Preference reply связывается с входящим `messageId` и client ID. Запрос preference без messageId получает400 до изменения предпочтения; актуальный WhatsApp webhook передаёт messageId. |
| `OrderStatusNotificationService` | `order_client_message_occurrences` хранит operation ID по order+logical kind, отдельную generation события и подтверждение. Pending сохраняет ID даже при изменении fallback status, business generation, шаблона и маршрута. Подтверждённая операция меняется только для более нового события. |
| `OrderServiceImpl` publication | Captured review ID + `publishedMarkedAt` текущего нового события передаются в afterCommit callback, включая отдельную транзакцию загрузки. Повтор уже опубликованного отзыва не создаёт событие. Telegram сохраняет opt-out keyboard, canonical payload включает текст и кнопку. |
| `PaymentRouteChangeNotificationWorker` | Persisted paymentLink ID из новой outbox записи. Старые записи не приобретаются; повтор enqueue старой строки не снимает карантин. |
| `ScheduledClientMessageService` | Два существующих keyed вызова: ordinary state ID+scenario+sentCount и bad-review state ID+persisted deliveryToken. Сами немедленные повторы статуса проходят тот же OrderStatusNotificationService. |
| `CommonInvoiceDeliveryService`, `PaymentSuccessClientNotifier` | Финансовый owner: persisted invoice occurrence V303 и payment-success:<paymentLinkId>; независимые подтверждения и legacy UNKNOWN fence описывает финансовый runbook. |
| `ManagerControlClientSendWorkflow`, `ManagerControlClientReplyWorkflow` | Сохранённая manager action/reply operation; keyed sender вызывается после подготовки. Повтор UNKNOWN не формирует новое событие. |

`ClientMessageDelivery` — публичный scalar API, без JPA Company в контракте. `ClientChatMessageSender` сохраняет старые Company overloads для совместимости. Его unkeyed `send()`/`sendToPlatform()` больше не имеют production бизнес-вызовов; единственный low-level3arg вызов находится внутри этого compatibility adapter и fail-closed. `WhatsAppServiceImpl` отклоняет3arg вызовы до HTTP и считает `otziv.whatsapp.legacy_send.rejected{kind}`. `WhatsAppProducerIdentityContractTest` разбирает production Java AST и запрещает новые unkeyed/null-key вызовы; при прогоне печатает полный список и counts.

## Транзакции и подтверждения

WA reservation шифрует полные первые скаляры envelope. `freeze()` участвует в producer TX; `freezeForDispatch()` использует REQUIRES_NEW, включая вызов из afterCommit, где старые transaction resources ещё привязаны. Low-level4arg transport только проверяет сохранённый envelope и ничего не резервирует. Повтор после изменения Company маршрута использует первоначальную WA reservation.

Order occurrence резервируется REQUIRES_NEW **до** sender; у таблицы намеренно нет FK к уже заблокированному Order, чтобы независимый commit не ждал родительскую блокировку своей транзакции. Если отправка или локальный commit подтверждения завершились неопределённо, сохранённый ID остаётся. Поздний receipt старой generation не подтверждает новую. Telegram/MAX используют существующий persistent PREPARED/UNKNOWN fence: provider получает не более одного нерешённого dispatch; автоматический exactly-once для неидемпотентного провайдера не обещается. Missing/invalid receipt не считается успехом.

## Исторический карантин V305

Миграция не предполагает, что отсутствие receipt означает отсутствие доставки. У старых Orders `client_message_generation=0`. Ordinary notification не резервирует новый ID и не вызывает ни один провайдер. Даже новый запрос смены статуса сам по себе не снимает этот карантин: прежний fallback мог быть результатом UNKNOWN. Только действительно новая JPA insert получает generation1; следующие подтверждённые lineage transitions увеличивают её до dispatch.

У прежних `payment_route_change_notification_outbox` строк `operation_identity_ready=FALSE`. Due scan и direct acquire исключают их. Новое `enqueue` вставляет TRUE, но INSERT IGNORE никогда не повышает старую строку. Существующие sent/skipped timestamps, attempts и старые ошибки остаются неизменными. Это карантин, а не утверждение о доставке или её отсутствии.

Перед включением dispatchers: остановить старые writers, дождаться завершения/классификации in-flight запросов, зафиксировать backup/checksum и read-only inventory. Не запускать одновременно старый и новый sender. Проверить нулевой legacy rejection counter и отсутствие старых бизнес-callers. Для каждой старой неопределённой операции сверить реестр gateway/провайдера и операционный журнал; неизвестную историю сохранять неопределённой. Общего UPDATE, превращающего все0/FALSE в разрешённые операции, здесь намеренно нет. Новая ручная отправка требует отдельного явного решения через новую форму, а не повторного запуска старого задания.

## Bounded read-only inventory без payload/PII

Подключение только к выбранной локальной/maintenance БД, credentials через защищённое окружение или secret mount. Запросы возвращают ID и reason, без текста, адресата или ciphertext. Зафиксировать `through` один раз, сохранять последний ID каждой страницы и продолжать `after < id <= through`; размер страницы 500. Сумма числа строк страниц — scanned. Inventory изменяет0 строк; conflicts0, потому что не делает writes. Старые V288/V300 и Flyway history/checksum этим не затрагиваются.

```sql
-- Orders: сохранить значение through из первого запроса.
SELECT COALESCE(MAX(order_id),0) AS through_id FROM orders;
SELECT order_id, 'LEGACY_ORDER_OPERATION_UNVERIFIED' AS reason
FROM orders
WHERE order_id > :after_id AND order_id <= :through_id
  AND client_message_generation = 0
ORDER BY order_id LIMIT 500;

-- Route jobs: отдельный cursor/high-water.
SELECT COALESCE(MAX(payment_link_id),0) AS through_id
FROM payment_route_change_notification_outbox;
SELECT payment_link_id, order_id, 'LEGACY_ROUTE_OPERATION_UNVERIFIED' AS reason
FROM payment_route_change_notification_outbox
WHERE payment_link_id > :after_id AND payment_link_id <= :through_id
  AND operation_identity_ready = FALSE AND sent_at IS NULL AND skipped_at IS NULL
ORDER BY payment_link_id LIMIT 500;

-- Неопределённые новые order occurrences: keyset по (order_id,logical_kind).
SELECT order_id, logical_kind, operation_id, generation,
       'UNCONFIRMED_SAVED_OPERATION' AS reason
FROM order_client_message_occurrences
WHERE confirmed = FALSE
  AND (order_id > :after_order_id
       OR (order_id = :after_order_id AND logical_kind > :after_kind))
ORDER BY order_id, logical_kind LIMIT 500;
```

Keyset ограничивает возвращаемые данные и будущие mutation batches; отсутствие predicate index может потребовать чтения многих строк. ALTER TABLE и миграционные predicate scans не объявляются bounded по времени или lock duration. Backup/restore и dry-run старых performer очередей имеют отдельный [runbook](LEGACY_QUEUE_MAINTENANCE.md).

## Проверки

`OrderNotificationOccurrencesMySqlIntegrationTest` использует настоящий MySQL и Spring transaction proxies: параллельная reservation, перезапуск, fallback generation, stale receipt, независимый commit до доставки, поздний rollback бизнес-транзакции, подтверждение с ошибкой commit, generation0 без provider, миграция старой route job и невозможность повторным enqueue снять карантин. `WhatsAppBusinessOperationsMySqlIntegrationTest` отдельно проверяет реальный cipher/encrypted envelope, изменение адресата/текста, manual actor binding, lead afterCommit и runtime legacy counter.

Команда узкого прогона (Java26, Docker для MySQL):
`./mvnw.cmd -B -ntp -Dtest=OrderNotificationOccurrencesMySqlIntegrationTest,WhatsAppBusinessOperationsMySqlIntegrationTest,ClientChatMessageSenderTest,OrderStatusNotificationServiceTest,PaymentRouteChangeNotificationWorkerTest,OrderServiceImplTest,OrderStatusTransitionServiceTest,WhatsAppProducerIdentityContractTest,GroupReplyServiceImplTest,LeadServiceImplOwnerReadScopeTest,ScheduledClientMessageServiceTest test`

Фактические журналы и counts — `.codex-tmp/remediation-completion-20260907/`; итоговый whole-project verify выполняется отдельно после общего source freeze. Не следует складывать пересекающиеся targeted наборы.

Финальный scoped срез: **225/225 PASS, 0 skipped**, Java26, 2026-09-07 12:26:27 UTC, `order-notification-completion-v3.log`. Включены15 MySQL cases (5 order occurrence/cutover +10 WA encrypted operations),46 sender,58 scheduled,18 notification,60 status transition,11 publication owner,4 route worker,10 group reply,2 lead owner,1 source identity guard. AST зафиксировал12 keyed бизнес-call sites в10 owner/method группах и0 unkeyed/null-key business calls; compatibility adapter исключён явно. Предыдущий v2 содержал6 ошибок только новых fixtures: status success вместо протокольного ok и Mockito spy над Spring proxy. Исправлены fixtures; реальный JDBC fault теперь возникает после UPDATE подтверждения и откатывается настоящим transaction manager.

## Независимый review и точные пределы

Независимый read-only review `backend_architecture` не обнаружил новой подтверждённой регрессии: проверены FOR UPDATE reservation, CAS receipt, отсутствие FK deadlock, historical generation0 и captured review occurrence. До receipt незавершённая операция намеренно объединяет последующие business generations; это не очередь, которая гарантирует отдельную доставку каждого возникшего события.

Publication callback остаётся прежним in-memory afterCommit вызовом. Crash после commit публикации и до callback может потерять само обращение к reservation; стабильная идентичность защищает уже начатый повтор, но не превращает этот callback в durable outbox. Provider invocation в этой callback-ветке сохраняет прежний REQUIRES_NEW и Order lock. Эти ограничения существующего delivery lifecycle не объявляются устранёнными P15 и не маскируются утверждением exactly-once.
