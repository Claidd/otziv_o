# Отметка оплаты, перерасчёт резервов, исправления и уведомления
> Для ИИ-агентов. Состояние кода: рабочее дерево main на 2026-11-10.

Правила чтения ссылок. Java-файлы указаны как `ИмяКласса.java:строка`; имя класса в репозитории уникально, полный путь от корня — в «Карте кода». Остальные файлы — с префиксами: `FE/` = `frontend/src/app/`, `MOB/` = `mobile/src/app/`, `MIG/` = `backend/src/main/resources/db/migration/`, `TEST/` = `backend/src/test/java/com/hunt/otziv/`. Номера строк — по файлам на диске, включая незакоммиченные правки от 2026-11-10.

Незакоммичено на 2026-11-10 (в `git status`): эндпоинт `manual-payment-other-recipients`, «другие получатели» в координаторе, уведомления вокруг отчёта владельцу, кнопка спора `common-payment-dispute:`; новые файлы `CommonInvoiceRecipientReceipts.java`, `CommonInvoiceRecipientReceiptsService.java`, `CommonManualPaymentOtherRecipientsResponse.java`, `CommonInvoicePaymentDisputeService.java`, `MIG/V1_10_395__common_invoice_recipient_dispute.sql`.

## Коротко — 8–12 правил, которые нельзя нарушать

1. Оплату по банковской ссылке подтверждает только банк (webhook или фоновая сверка). Отметить вручную «получил владелец» при банковском маршруте нельзя: `CommonInvoiceManualPaymentWorkflow.java:905-910`, `ManualCardPaymentWorkflow.java:383-388`. Исключение с 2026-11-12 — только счёт одного заказа и только перевод **мимо ссылки на карту владельца**: если банковский профиль менеджера разрешает владельцу принимать переводы на карту (`OwnerCardPolicy.allows`: политика `MANUAL_UNTIL_LIMIT_THEN_TBANK` и заполненные реквизиты), менеджер может отправить запрос владельцу; заказ закрывается только после подтверждения владельца в Telegram и после проверки ссылки в банке. Для профиля «только банк» (`T_BANK_ONLY`, владелец-ИП) владельца нет в списке и сервер отказывает. Общий счёт правило не меняет.
2. Ручная отметка общего счёта принимает только весь остаток. Частичная сумма отклоняется, разбиение остатка между несколькими получателями разрешено: `CommonManualPaymentAttributionCoordinator.java:392-394`.
3. Клиент не выбирает произвольный платёжный профиль: каждая строка сверяется со списком кандидатов, построенным сервером: `CommonManualPaymentAttributionCoordinator.java:356-367`.
4. Менеджер не зачисляет деньги владельцу. Его запрос становится отчётом в `common_owner_receipt_approval` и ждёт OWNER/ADMIN: `CommonOwnerReceiptApprovals.java:75-106,115-122`.
5. Пока у счёта живая банковская сессия, ручная отметка сначала сверяется с банком; при платёжном движении ничего не зачисляется: `CommonInvoiceManualPaymentWorkflow.java:252-294,529-578`.
6. Фактическое поступление — неизменяемая строка `contractor_actual_payment_attributions`. Превышение лимита не отклоняет платёж, а сохраняется как `projectedOverrunKopecks`: `ContractorActualPaymentAttributionService.java:149-153,314-315`.
7. Если деньги получил не получатель из счёта, исходный резерв закрывается статусом `CANCELED`, фактическому получателю создаётся распределение `ACTUAL_PAYMENT`: `ContractorActualPaymentAttributionService.java:272-282,1353-1368,1561-1602`.
8. Выданный маршрут платёжного задания завершается в режиме учёта, сохранённом при выдаче, даже если глобальный режим потом сменился: `CommonManualPaymentAttributionCoordinator.java:290-305`.
9. Повтор запроса обязан совпасть по ключу операции и всем данным; иначе 409: `CommonManualPaymentAttributionCoordinator.java:177-255`.
10. Ошибочное зачисление исправляет только ADMIN. Спор получателя деньги не возвращает: `ManualReceiptCorrections.java:22`, `CommonInvoicePaymentDisputeService.java:16-20`.
11. Уведомления получателям общего счёта идут только из outbox и только по LIVE-строкам с пользователем: `CommonInvoicePaymentNotificationOutboxRepository.java:71-77`.
12. Каждая точка входа проверяет видимость счёта и политику цели: `CommonInvoiceDeliveryService.java:780-806`, `ContractorPaymentTargetAccessPolicy.java:87-96`.

## Карта кода — таблица: класс/файл → за что отвечает

| Файл | За что отвечает |
|---|---|
| **`backend/src/main/java/com/hunt/otziv/common_billing/controller/`** | |
| `CommonManualPaymentAttributionController.java` | REST ручной отметки общего счёта с получателями |
| `CommonOwnerReceiptDecisionController.java` | Отклонение отчёта «деньги получил владелец» |
| `CommonBillingAdminController.java` | Старые эндпоинты `/paid`, `/attention/manual-card-paid` (режим без учёта получателей) |
| `CommonManualPaymentTaskReturnController.java` | Возврат по платёжному заданию общего счёта (ADMIN/OWNER) |
| **`backend/src/main/java/com/hunt/otziv/common_billing/service/`** | |
| `CommonBillingService.java` | Фасад модуля, делегирует в рабочие процессы |
| `CommonInvoiceManualPaymentWorkflow.java` | Ручная отметка, сверка с банком перед отметкой, отчёт владельцу |
| `CommonManualPaymentAttributionCoordinator.java` | Список получателей, проверка запроса, запись поступления, история |
| `CommonOwnerReceiptApprovals.java` | Таблица `common_owner_receipt_approval`: отчёт, решение, отпечаток |
| `CommonInvoiceSettlementService.java` | Webhook банка для общего счёта, `closePaidInvoice`, постановка уведомлений |
| `CommonInvoiceTochkaReconciliationService.java`, `CommonBillingReminderScheduler.java` | Фоновая сверка попыток Точки раз в минуту |
| `CommonInvoiceDeliveryService.java` | Видимость счёта по ролям, отправка клиенту «оплата получена» |
| `CommonInvoicePaymentNotificationDeliveryService.java` | Рабочий цикл outbox уведомлений (15 с) |
| `CommonInvoiceRecipientReceiptsService.java` | Чтение доставленного уведомления и отметка спора |
| `CommonInvoiceReceiptCorrection.java` | Повторное открытие общего счёта при отмене зачисления |
| **`backend/src/main/java/com/hunt/otziv/common_billing/`** `api/CommonInvoiceRecipientReceipts.java`, `repository/CommonInvoicePaymentNotificationOutboxRepository.java`, `dto/CommonManualPaymentAttributionRequest.java`, `dto/CommonManualPaymentOtherRecipientsResponse.java` | Контракт спора; JDBC outbox; DTO запроса; DTO «других получателей» |
| **`backend/src/main/java/com/hunt/otziv/contractor_payments/service/`** | |
| `ContractorActualPaymentAttributionService.java` | Запись фактического получателя, перерасчёт резервов, режимы учёта |
| `ContractorActualPaymentAttributionFlowPolicy.java` | Выбор «новый поток с получателями» / «старое подтверждение» |
| `ContractorPaymentTargetAccessPolicy.java` | Запрет владельцу управлять данными ADMIN/OWNER |
| `ContractorPaymentAccountingService.java`, `ContractorPaymentAccountingPhaseService.java` | Примитивы резерва (резерв, подтверждение, возврат, освобождение); фаза учёта |
| `ManualReceiptCorrections.java` | Исправление ошибочного зачисления |
| **`backend/src/main/java/com/hunt/otziv/contractor_payments/`** `controller/ManualReceiptCorrectionController.java`, `repository/ContractorPaymentProfileRepository.java` | REST исправлений; запросы команды и исполнителей компаний |
| **`backend/src/main/java/com/hunt/otziv/payments/controller/`** `AdminPaymentController.java`, `ManagerManualPaymentReviewController.java`, `SpecialistManualPaymentReviewAdminController.java`, `ManualTransferReceiptController.java`, `TbankWebhookController.java`, `TochkaWebhookController.java`, `PublicPaymentController.java` | REST отметки одного заказа, споры, админ-разбор, один перевод на несколько заказов, webhook, публичная страница оплаты |
| **`backend/src/main/java/com/hunt/otziv/payments/service/`** | |
| `BankPaymentReconciliationWorkflow.java`, `PaymentBankStatusReconciliationService.java` | Webhook и фоновая сверка ссылок одного заказа |
| `PaymentLinkSettlementService.java` | Применение статуса банка к ссылке и заказу |
| `ManualCardPaymentWorkflow.java`, `ManualCardRoutePolicy.java` | Ручная отметка счёта одного заказа |
| `OwnerManualCardApprovalWorkflow.java` | Вход менеджера; подтверждение владельца по кнопке Telegram |
| `ManualPaymentConfirmationWorkflow.java` | Старое подтверждение ручной ссылки, закрытие «не оплачено» |
| `SpecialistManualPaymentReviewService.java`, `SpecialistManualPaymentReviewSettlementService.java`, `SpecialistManualPaymentReviewScheduler.java` | Отметка менеджера с окном спора 24 часа |
| `ManualTransferReceiptService.java` | Один перевод, распределённый на несколько заказов |
| `ManualPaymentTaskReceiptIntegrationService.java` | Зачёт суммы в платёжное задание |
| `ManualPaymentRecipientTelegramNotificationService.java` | Текст и отправка уведомления получателю |
| `CommonInvoicePaymentDisputeService.java` | Спор получателя по общему счёту |
| `SpecialistManualPaymentReviewTelegramCallbackService.java` | Разбор двух префиксов кнопки спора |
| `ManualCardPaymentReviewNotificationService.java` | Напоминания и Telegram владельцам/администраторам |
| `PaymentIssueReminderService.java` | Задача сверки менеджеру, владельцам, администраторам |
| `ManualPaymentTaskRouteErrors.java` | Коды отказов 409: `PAYMENT_ROUTE_STALE`, `ACTUAL_RECIPIENT_REQUIRED` |
| **Прочее** `backend/src/main/java/com/hunt/otziv/p_products/payment/service/OrderPaymentCancellationService.java`, `backend/src/main/java/com/hunt/otziv/t_telegrambot/service/TelegramService.java`, `backend/src/main/java/com/hunt/otziv/payments/dto/ManualPaymentReviewView.java`, `backend/src/main/java/com/hunt/otziv/payments/dto/ManagerManualCardPaymentResultResponse.java`, `backend/src/main/java/com/hunt/otziv/common_billing/service/CommonInvoiceCancellationService.java`, `backend/src/main/java/com/hunt/otziv/common_billing/service/CommonInvoicePaymentRouteWorkflow.java` | Откат оплаты заказа; диспетчер callback Telegram; правило «спор разрешён»; ответ менеджеру после отметки заказа; два других места вызова `getState` |
| `MIG/V1_10_273__common_invoice_payment_notification_outbox.sql`, `MIG/V1_10_389__common_owner_receipt_approval.sql`, `MIG/V1_10_394__manual_receipt_corrections.sql`, `MIG/V1_10_395__common_invoice_recipient_dispute.sql` | Таблицы outbox, отчёта владельцу, исправлений, колонки спора |
| `FE/features/admin/common-billing/common-manual-payment-attribution-modal.component.{ts,html}`, `FE/shared/common-manual-payment-route-refresh.ts`, `FE/core/common-manual-payment-attribution.api.ts` | Окно сайта для общего счёта |
| `FE/features/manager/manager-manual-card-payment-modal.component.ts` | «оплатили» одного заказа: обёртка над окном общего счёта, даёт ему получателей заказа |
| `FE/features/admin/tbank-payments/manual-receipt-correction.component.{ts,html}` | Блок «Исправить ручное поступление» на странице «Платежи, возвраты и чеки» |
| `MOB/shared/mobile-common-manual-payment-dialog.component.{ts,html}`, `MOB/shared/mobile-common-manual-payment-flow.service.ts`, `MOB/shared/common-manual-payment-route-refresh.ts`, `MOB/core/common-billing.api.ts` | Окно приложения для общего счёта |

## Оплата по банковской ссылке

Никто ничего не нажимает. Два входа: webhook и фоновая сверка.

**Ссылка одного заказа**

| Шаг | Что происходит | Где |
|---|---|---|
| 1 | Webhook T-Bank: проверка подписи, поиск ссылки по `OrderId`, затем по `PaymentId` | `BankPaymentReconciliationWorkflow.java:129-138,435-446` |
| 2 | Ссылка не найдена → событие передаётся общему счёту | `BankPaymentReconciliationWorkflow.java:139-146` |
| 3 | Под блокировкой заказа и ссылки: проверка терминала и суммы, затем `applyBankStatus` | `BankPaymentReconciliationWorkflow.java:243-275` |
| 4 | `CONFIRMED` без успеха, кода `0`, `PaymentId` или суммы → 400, нужна сверка | `PaymentLinkSettlementService.java:197-202` |
| 5 | `confirmPayment`: повтор не меняет состояние; второй подтверждённый платёж, закрытая ссылка, расхождение суммы — отдельные ветки без закрытия заказа | `PaymentLinkSettlementService.java:284-310` |
| 6 | Тестовый платёж или выключенное применение → `TEST_CONFIRMED`, заказ не трогается | `PaymentLinkSettlementService.java:311-320` |
| 7 | Заказ ещё не готов к оплате → предоплата | `PaymentLinkSettlementService.java:325-329` |
| 8 | Иначе заказ переводится в оплату, ссылка `CONFIRMED`, позиция общего счёта синхронизируется | `PaymentLinkSettlementService.java:330-341` |
| 9 | Webhook Точки: сначала проверка JWT, потом поиск; та же схема | `BankPaymentReconciliationWorkflow.java:160-230` |
| 10 | Фоновая сверка каждые 300 000 мс: статусы `INITIATED`, `AUTHORIZED`, `NEEDS_RECONCILIATION`, частичные возвраты; уже рассчитанные — раз в 24 часа | `PaymentBankStatusReconciliationService.java:27-33,40-100` |
| 11 | Ошибка применения статуса → ссылка `NEEDS_RECONCILIATION`, причина в `lastError` | `BankPaymentReconciliationWorkflow.java:342-378` |

**Общий счёт**

| Шаг | Что происходит | Где |
|---|---|---|
| 1 | Webhook T-Bank: поиск счёта по `OrderId`/`PaymentId`, сверка с текущей привязкой, терминалом и суммой | `CommonInvoiceSettlementService.java:885-934` |
| 2 | `CONFIRMED`, счёт уже `PAID` → попытка помечается `APPLIED` | `CommonInvoiceSettlementService.java:949-953` |
| 3 | `CONFIRMED`, счёт в конечном статусе (`UNPAID` без переоценки, `BAN`, `DISABLED`…) → `NEEDS_ATTENTION`, «late_tbank_payment» | `CommonInvoiceSettlementService.java:954-961` |
| 4 | Подтверждённая сумма ≠ остатку → `NEEDS_ATTENTION`, «payment_amount_changed_after_confirmation» | `CommonInvoiceSettlementService.java:965-973` |
| 5 | Не все заказы готовы → предоплата, заказы не закрываются | `CommonInvoiceSettlementService.java:974-977` |
| 6 | Иначе `closePaidInvoice`, попытки `CONFIRMED` → `APPLIED` | `CommonInvoiceSettlementService.java:978-982` |
| 7 | Точка: webhook и сверка раз в минуту → `applyConfirmedTochkaPayment` с теми же ветками | `CommonInvoiceSettlementService.java:494-550,770-856`; `CommonBillingReminderScheduler.java:51-57`; `CommonInvoiceTochkaReconciliationService.java:389` |
| 8 | `closePaidInvoice`: заказы закрываются оплатой, счёт `PAID`, клиентское уведомление в outbox, задание завершается при достижении цели, открываются следующие заказы | `CommonInvoiceSettlementService.java:1660-1710` |
| 9 | Банковское закрытие идёт без `finalAttribution`: строк получателей и уведомлений получателям нет | `CommonInvoiceSettlementService.java:1652-1654,1700-1704` |
| 10 | Счёт с зафиксированными реквизитами сотрудника (LIVE) без атрибуции этим путём не закрывается → 409 | `CommonInvoiceSettlementService.java:1661-1663` |

## Ручная отметка общего счёта — по шагам

**Эндпоинты** (`/api/common-billing/invoices/{invoiceId}`, роли ADMIN/OWNER/MANAGER, ответы `no-store`): `CommonManualPaymentAttributionController.java:31-32,94-99`.

| Метод | Назначение | Где |
|---|---|---|
| `GET manual-payment-mode` | `true`, если учёт получателей обязателен или у счёта выданный маршрут задания | `CommonManualPaymentAttributionController.java:39-47` |
| `GET manual-payment-options` | Остаток, кандидаты, ключ по умолчанию, история, ожидающий отчёт владельцу | `CommonManualPaymentAttributionController.java:49-55`; `CommonInvoiceManualPaymentWorkflow.java:699-706` |
| `GET manual-payment-other-recipients` (новое) | Люди вне счёта, по отдельной кнопке | `CommonManualPaymentAttributionController.java:57-64` |
| `POST paid-with-attributions` | Обычная отметка | `CommonManualPaymentAttributionController.java:66-76` |
| `POST attention/manual-card-paid-with-attributions` | Отметка только для счёта в `NEEDS_ATTENTION` с конфликтом одиночных платежей | `CommonManualPaymentAttributionController.java:78-92`; `CommonInvoiceManualPaymentWorkflow.java:770-772,795-798` |
| `POST owner-reports/{reportId}/reject` | Отклонить отчёт владельцу (ADMIN/OWNER) | `CommonOwnerReceiptDecisionController.java:20-26` |

Каждый метод сначала вызывает `requireCanManageCommonInvoice` и `requireAttributionFlowOrFrozenTask`: `CommonManualPaymentAttributionController.java:51-53,72-74`. Если учёт получателей выключен и маршрут не от задания, новый поток отвечает 409, а работает старый `/paid`: `ContractorActualPaymentAttributionFlowPolicy.java:35-49`, `CommonBillingAdminController.java:195-205`.

**Время поступления ставит сервер** (с 2026-11-10). Раньше оно бралось с часов устройства: у сотрудника в другом
часовом поясе оплата записывалась на несколько часов раньше (иногда прошлым днём), а при часах впереди серверных
сервер отказывал «Время поступления не может быть в будущем». Теперь:

- первый запрос операции получает текущее время сервера (до миллисекунд);
- повтор того же запроса (тот же `idempotencyKey`) получает время, уже записанное в поступлении
  (`CommonManualPaymentAttributionCoordinator.recordedReceiptTime`), поэтому остаётся тем же запросом;
- решение владельца по запросу менеджера получает время запроса менеджера (`CommonOwnerReceiptApprovals.reportedReceiptTime`);
- время определяется на входе и ещё раз под блокировкой счёта в каждом шаге записи — повтор, пришедший одновременно с
  первым запросом, после блокировки видит уже записанное время.

Сайт и приложение при этом шлют время в рабочем часовом поясе (`shared/business-date.ts`, `businessDateTimeStamp`) —
это значение сервер не читает, но оно остаётся осмысленным для старых серверов и в журналах. Формы администратора
«расчёт с сотрудником» и «подтверждение источника» по-прежнему принимают время, введённое человеком.

**Правила запроса** (`CommonManualPaymentAttributionRequest`)

| Правило | Ответ при нарушении | Где |
|---|---|---|
| `finalAccountingAcknowledged` и `paymentReceived` равны `true` | 400 «Подтвердите фактическое поступление и финальное изменение расчётов» | `CommonManualPaymentAttributionRequest.java:20-21`; `CommonInvoiceManualPaymentWorkflow.java:1101-1104` |
| `reason` непустой, до 500 символов | 400 | `CommonInvoiceManualPaymentWorkflow.java:1108-1111`; `CommonManualPaymentAttributionCoordinator.java:876-882` |
| `effectiveAt` обязателен в запросе, но его значение не используется: время поступления ставит сервер | — | `CommonInvoiceManualPaymentWorkflow.withServerReceiptTime` |
| `idempotencyKey`, `rowKey`: `[A-Za-z0-9._-]`, до 48 символов | 400 «Некорректный …» | `CommonManualPaymentAttributionCoordinator.java:75,999-1005` |
| От 1 до 20 строк; получатель не повторяется; сумма строки > 0 | 400 | `CommonManualPaymentAttributionCoordinator.java:884-895,351-359` |
| Сумма строк равна остатку счёта | 400 «Сумма по получателям должна быть равна остатку общего счета» | `CommonManualPaymentAttributionCoordinator.java:392-394` |
| У владельца нет `recipientProfileId`, у сотрудника есть | 400 | `CommonManualPaymentAttributionCoordinator.java:899-904` |
| `receiptUrl` — только http/https с хостом, без логина, до 1024 | 400 | `CommonManualPaymentAttributionCoordinator.java:971-990` |
| Строки задания и того же человека напрямую в одном запросе | 400 «Нельзя разбивать один платёж между заданием и тем же фактическим получателем» | `CommonManualPaymentAttributionCoordinator.java:422-449` |

**Как строится список получателей** (`context`)

| Кандидат | Условие | Где |
|---|---|---|
| Владелец (`OWNER`) | Всегда в контексте | `CommonManualPaymentAttributionCoordinator.java:481-493` |
| Платёжное задание (`TASK:…`) | У счёта выданный маршрут задания; обычный псевдоним цели задания (владелец или профиль) убирается | `CommonManualPaymentAttributionCoordinator.java:494-522,599-618` |
| Получатель из счёта | Счёт выставлен на реквизиты сотрудника (`CONTRACTOR_PAYMENT_PROFILE`) | `CommonManualPaymentAttributionCoordinator.java:456-461,524-533` |
| Специалист заказа, менеджер заказа | По каждой активной позиции счёта | `CommonManualPaymentAttributionCoordinator.java:538-573` |
| Менеджер аккаунта счёта | Если задан | `CommonManualPaymentAttributionCoordinator.java:574-587` |
| Команда менеджеров счёта | Активный пользователь, профиль включён, роль SPECIALIST | `CommonManualPaymentAttributionCoordinator.java:588-597`; `ContractorPaymentProfileRepository.java:19-24` |
| «Другие получатели» (новое) | Вся активная команда менеджеров счёта (профиль может быть выключен) и исполнители любых заказов тех же компаний либо закреплённые за ними | `CommonManualPaymentAttributionCoordinator.java:652-685`; `ContractorPaymentProfileRepository.java:26-57` |

- В обычный список «другие» не попадают: `options` передаёт только профили, названные в запросе, а без запроса — никого: `CommonManualPaymentAttributionCoordinator.java:94-116,1066-1091`.
- `GET manual-payment-other-recipients` строит контекст с `OtherRecipients.EVERYONE` и отдаёт только тех, кто не получатель счёта, не участник заказов, не владелец и не задание; для каждого — признаки `previousPerformer`, `teamMember`: `CommonManualPaymentAttributionCoordinator.java:140-154,1175-1180`.
- При записи поступления `OtherRecipients.named(request)` подгружает только названные профили и только если они входят в команду или исполнителей; иначе кандидата нет и запрос отклоняется как устаревший маршрут — 409 `PAYMENT_ROUTE_STALE` «Платёжный маршрут изменился. Обновите форму и повторите проверку»: `CommonManualPaymentAttributionCoordinator.java:338,667-669,360-362`, `ManualPaymentTaskRouteErrors.java:30-36`.
- Разовый получатель действует на одно поступление, получатель счёта не меняется: `CommonManualPaymentOtherRecipientsResponse.java:5-8`.
- Любой профиль отфильтровывается, если текущему пользователю нельзя управлять его владельцем: `CommonManualPaymentAttributionCoordinator.java:718-722`.
- Для счёта с банковским маршрутом владелец из ответа убирается; если кандидатов не осталось — 409: `CommonManualPaymentAttributionCoordinator.java:117-124`. Причина: поступление владельцу по банковской ссылке подтверждает банк: `CommonOwnerReceiptApprovals.java:34-38`, `CommonInvoiceManualPaymentWorkflow.java:905-910`.
- Проверка присланной строки: кандидат найден, тип и профиль совпадают, пользователю разрешено управлять получателем: `CommonManualPaymentAttributionCoordinator.java:356-373`.
- Доступный лимит кандидата = обычный доступный остаток профиля + невыбранная часть резерва этого же счёта: `CommonManualPaymentAttributionCoordinator.java:746-769`.

**Ход `POST paid-with-attributions`** (`markPaidWithAttributions`)

| Шаг | Что происходит | Где |
|---|---|---|
| 1 | Проверка запроса | `CommonInvoiceManualPaymentWorkflow.java:254` |
| 2 | В запросе есть владелец → `stageOwnerReport`; если отчёт поставлен, счёт не закрывается, возвращается его текущее состояние | `CommonInvoiceManualPaymentWorkflow.java:255,375-410` |
| 3 | Текущая попытка Точки: наблюдение банка. Нет подтверждения «не оплачено» и ссылка не закрыта → 409 «Точка ещё не подтвердила отсутствие оплаты» | `CommonInvoiceManualPaymentWorkflow.java:256-260,296-309` |
| 4 | Точка подтвердила: попытка `ARCHIVED` с наблюдением (ссылка ещё открыта) или `EXPIRED`, затем шаг 8 | `CommonInvoiceManualPaymentWorkflow.java:261-272` |
| 5 | Текущая сессия T-Bank: попытка помечается `CANCELING`, счёт временно `NEEDS_ATTENTION` с маркером сверки | `CommonInvoiceManualPaymentWorkflow.java:274,479-499` |
| 6 | `GetState`: конечный безопасный статус → можно; статус с деньгами → стоп; `NEW`/`FORM_SHOWED` → `Cancel`, нужен ответ `CANCELED` | `CommonInvoiceManualPaymentWorkflow.java:529-578` |
| 7 | Сверка не прошла → счёт остаётся `NEEDS_ATTENTION`, ответ 409/502 с текстом банка; прошла → статус счёта восстановлен | `CommonInvoiceManualPaymentWorkflow.java:279-282,628-666` |
| 8 | `markPaidWithAttributionsLocked`: блокировки заказов, ссылок и счёта; счёт уже `PAID` → только точный повтор, иначе 409 | `CommonInvoiceManualPaymentWorkflow.java:333-344,821-829` |
| 9 | Проверки статуса (`READY`, `INVOICED`, `REMINDER`, `PARTIALLY_PAID`, `UNPAID`), запрет владельца на банковском маршруте, принятие отчёта владельцу | `CommonInvoiceManualPaymentWorkflow.java:350-359,952-968`; `CommonInvoiceSettlementService.java:111` |
| 10 | Нет текущей привязки T-Bank и нет незавершённых попыток в реестре | `CommonInvoiceManualPaymentWorkflow.java:361-362,1115-1127` |
| 11 | Запись ручного подтверждения на счёте и позициях, архив текущей попытки, ссылка на подтверждение `COMMON_INVOICE:<id>:<ключ>` на позициях | `CommonInvoiceManualPaymentWorkflow.java:363-365,996-1011,1037-1053` |
| 12 | Закрытие счёта вместе с `recordFinalReceipt`; после записи строк — уведомления получателям в outbox | `CommonInvoiceManualPaymentWorkflow.java:371`; `CommonInvoiceSettlementService.java:1700-1704,1732-1739` |

Для счёта, отложенного из-за плохого отзыва, действует отдельная проверка: без владельца, сумма равна выставленной и текущей стоимости заказов: `CommonInvoiceManualPaymentWorkflow.java:311-331,345-349`.

**Отчёт «деньги получил владелец»** (`common_owner_receipt_approval`, `MIG/V1_10_389__common_owner_receipt_approval.sql:1-17`)

| Ситуация | Поведение | Где |
|---|---|---|
| Менеджер шлёт запрос с владельцем | Проверка строк по кандидатам и суммы, вставка строки с `pending_invoice_id`; счёт не закрыт | `CommonOwnerReceiptApprovals.java:84-105` |
| На счёт один ожидающий отчёт | Уникальный ключ `pending_invoice_id` | `MIG/V1_10_389__common_owner_receipt_approval.sql:14` |
| Отпечаток | SHA-256 от маршрута, режима, распределения, суммы, оплаченного и позиций счёта | `CommonOwnerReceiptApprovals.java:137-145` |
| Новый отчёт | После коммита — напоминание и Telegram владельцам и администраторам | `CommonInvoiceManualPaymentWorkflow.java:392-407` |
| OWNER/ADMIN повторяет тот же запрос | Отчёт `ACCEPTED` в одной транзакции с закрытием счёта; напоминания удаляются | `CommonOwnerReceiptApprovals.java:114-122`; `CommonInvoiceManualPaymentWorkflow.java:412-417` |
| Запрос или состав счёта изменились | 409 «Счёт или запрос изменился…» / «Сначала рассмотрите запрос владельцу…» | `CommonOwnerReceiptApprovals.java:79-81,119-120` |
| Есть ожидающий отчёт, приходит другой запрос | 409 «Сначала рассмотрите ожидающий запрос владельцу» | `CommonOwnerReceiptApprovals.java:108-112` |
| OWNER/ADMIN сам указывает владельца, отчёта нет | Отчёт не создаётся, зачисление сразу | `CommonOwnerReceiptApprovals.java:84` |
| Менеджер пытается провести владельца в обход | 409 «Поступление владельцу ожидает его подтверждения» | `CommonOwnerReceiptApprovals.java:117` |
| Отклонение | `REJECTED`, напоминания удаляются, автору отчёта — напоминание и Telegram; счёт остаётся неоплаченным | `CommonInvoiceManualPaymentWorkflow.java:419-435` |
| Ключ запроса уже рассмотрен | 409 «Этот запрос уже рассмотрен. Откройте форму заново» | `CommonOwnerReceiptApprovals.java:95-97` |

**Окна сайта и приложения**

| Что | Сайт | Приложение |
|---|---|---|
| Подтверждение | Одна кнопка «Оплачен — закрыть счёт», галочек нет; флаги ставятся при нажатии (`FE/features/admin/common-billing/common-manual-payment-attribution-modal.component.ts:172-177,435-439`) | Две обязательные галочки и кнопка «Учесть и закрыть счёт» (`MOB/shared/mobile-common-manual-payment-dialog.component.html:137-144,174-175`; `.ts:113-123`) |
| Причина | Необязательна, подставляется текст по выбору получателей (`…modal.component.ts:62-71,426`) | Обязательна (`MOB/shared/mobile-common-manual-payment-dialog.component.ts:118,321-323`) |
| Предвыбор | В режиме `STANDARD` выбран получатель из счёта (`…modal.component.ts:242-247`; `FE/shared/common-manual-payment-route-refresh.ts:46-53`) | Строка создаётся без получателя (`MOB/shared/common-manual-payment-route-refresh.ts:19-24`), хотя подсказка говорит «Изначально выбран получатель из счёта» (`…dialog.component.html:35-38`) |
| «+ Другой получатель» | Есть, список грузится один раз на окно, человек действует на эту оплату (`…modal.component.ts:289-328`; `.html:245-281`) | Вызова `manual-payment-other-recipients` в `mobile/src` нет |
| Разделение | Ссылка «Деньги пришли нескольким людям — разделить сумму» (`…modal.component.html:111-118`) | Кнопка «+ Разделить между получателями» (`…dialog.component.html:98-103`) |
| Владелец у менеджера | Подпись кнопки «Отправить владельцу на подтверждение» (`…modal.component.ts:158-163,176`) | Отдельной подписи нет |
| Отчёт владельцу | «Денег нет — отклонить» / «Деньги пришли — закрыть счёт»; имена разовых получателей догружаются (`…modal.component.html:29-60`; `.ts:251-255`) | «Подтвердить поступление» / «Отклонить запрос»; для разового получателя показывается ключ (`…dialog.component.html:17-27`) |
| Режимы | `STANDARD`, `TBANK_FALLBACK`, `TBANK_ROUTE_FALLBACK`; банковский маршрут → `TBANK_ROUTE_FALLBACK` → `paid-with-attributions` (`FE/features/admin/common-billing/common-billing.component.ts:1135-1138`; `FE/core/common-manual-payment-attribution.api.ts:141-143`) | `STANDARD`, `TBANK_FALLBACK`; банковский маршрут → `TBANK_FALLBACK` → `attention/manual-card-paid-with-attributions` (`MOB/features/common-billing.page.ts:802`; `MOB/core/common-billing.api.ts:123-125`) |
| После смены маршрута | Ключ операции новый, строки сброшены, причина и чек сохранены (`…modal.component.ts:462-469`) | То же, галочки сняты (`…dialog.component.ts:292-300`) |

Окно сайта открывается из `common-billing.component` (маршруты `admin/common-billing` для ADMIN/OWNER и `manager/common-billing` для ADMIN/OWNER/MANAGER) и из доски менеджера: `FE/app.routes.ts:218-225,385-392`, `FE/features/admin/common-billing/common-billing.component.html:1227-1235`.

## Ручная отметка счёта одного заказа — по шагам

| Шаг | Что происходит | Где |
|---|---|---|
| 1 | Окно `manager-manual-card-payment-modal` запрашивает `GET /api/manager/orders/{id}/manual-card-payment-context` (ADMIN/OWNER/MANAGER) | `AdminPaymentController.java:117-127`; `FE/features/manager/manager-manual-card-payment-modal.component.ts:181-225` |
| 2 | Выбор платёжного источника заказа; другой активный или подтверждённый способ оплаты → 409; заказ в активном общем счёте не принимается | `ManualCardPaymentWorkflow.java:132-146,209-227` |
| 3 | `manualCardPaymentContext`: получатель из счёта или задание, специалист и менеджер заказа, команда менеджера с включённым профилем (пока выбор не зафиксирован), владелец; предупреждение о превышении | `ContractorActualPaymentAttributionService.java:454-560` |
| 4 | Банковская ссылка без зафиксированного выбора: в ответе `bankLinkRoute=true`; владелец остаётся в кандидатах, только если профиль ссылки разрешает ему переводы на карту (`ownerCardReceiptAllowed` → `OwnerCardPolicy.allows(link.paymentProfile)`), иначе убран. Бумажный счёт: только владелец | `ManualCardRoutePolicy.manualCardPaymentContextForRoute`, `ownerCardReceiptAllowed`; `payments/service/OwnerCardPolicy.java` |
| 4а | `teamRecipientKeys` в ответе: ключи кандидатов, предложенных только как команда менеджера (не получатель счёта, не специалист и не менеджер заказа, не держатель резерва, не уже названный получатель). Окно показывает их отдельно, под «Другой получатель» | `ContractorActualPaymentAttributionService.manualCardPaymentContext`; `ManualCardPaymentContextResponse` |
| 5 | Окно сайта «Отметить оплату» — с 2026-11-12 это само окно общего счёта (`common-manual-payment-attribution-modal`, стили `FE/shared/payment-mark-modal.scss`): компонент заказа только передаёт ему источник данных (`PaymentMarkSource`) и сохраняет отметку через API заказа. Отсюда одинаковое поведение: владелец первым, получатель из счёта («в счёте») выбран сразу, остальные сотрудники менеджера — под «Другой получатель», комментарий необязателен (при пустом уходит «Оплата поступила получателю из счёта» / «Клиент перевёл деньги не получателю из счёта»; сервер по-прежнему требует непустую причину), кнопка «Инструкция». Отличия заказа: нет деления суммы между получателями (сервер принимает только полную сумму заказа), есть «Одним переводом оплачено несколько заказов». Если счёт — ссылка банка владельца (`bankLinkRoute`), никто не выбран заранее и никто не помечен «в счёте» (получатель ссылки — банк), а окно объясняет: оплату по ссылке банк отмечает сам; где владелец предложен — «выберите «Владелец», запрос уйдёт владельцу»; где не предложен — «на карту этот владелец оплату не принимает». Для заказа выбор владельца всегда даёт кнопку «Отправить владельцу на подтверждение» (и для роли OWNER/ADMIN: сервер заказа создаёт запрос при любом вошедшем пользователе) — `PaymentMarkSource.ownerApprovalConsequence`. Зафиксированный ранее выбор повторяется без изменений. Приложение показывает тот же текст над формой (`bankLinkNotice`). | `FE/features/manager/manager-manual-card-payment-modal.component.ts` (`manualCardPaymentWindowOptions`, `source`); `FE/features/admin/common-billing/common-manual-payment-attribution-modal.component.{ts,html}` (`PaymentMarkSource`) |
| 6 | `POST /api/manager/orders/{id}/confirm-manual-card-payment` → `submitManagerManualCardPaymentForOrder`; причина до 500 символов | `AdminPaymentController.java:129-150`; `OwnerManualCardApprovalWorkflow.java:91-103` |
| 7 | Получатель — владелец → запрос владельцу: запись `PENDING`, кнопка «Подтвердить поступление владельцу» в Telegram-группе менеджера; ответ `OWNER_APPROVAL_PENDING` | `OwnerManualCardApprovalWorkflow.java:123-126,148-222`; `ManualCardPaymentReviewNotificationService.java:299-330` |
| 8 | Владелец на банковской ссылке: профиль ссылки не разрешает карту владельца и запроса ещё нет → 409 «Оплату владельцу по банковской ссылке подтверждает банк». Профиль разрешает → обычный запрос владельцу (шаг 7), ссылка остаётся `CREATED`, заказ не закрыт | `OwnerManualCardApprovalWorkflow.requestOwnerManualCardPaymentApproval` |
| 8а | Подтверждение владельца по такому запросу идёт тем же путём, что шаг 13: наблюдение банка и отмена ссылки; оплата, найденная в банке, закрывает заказ банковской оплатой вместо ручной отметки. Ссылка-доказательство получает банковский профиль исходной ссылки (`payment_profile_id`), поэтому сумма входит в месячный лимит карты владельца (`sumManualReservedAndConfirmedForPeriod`). Запись разрешена и при исчерпанном лимите: деньги уже пришли; новые счета после этого продолжают уходить ссылкой банка | `ManualCardPaymentWorkflow.manualCardPaymentEvidenceLink`; `CommonInvoiceRouteSelector.ownerCardRemainingKopecks` |
| 9 | Подтвердить может активный OWNER/ADMIN, он же вошедший пользователь, только в группе менеджера компании заказа | `OwnerManualCardApprovalWorkflow.java:238-279,318-325` |
| 10 | Получатель — специалист или менеджер → запись проверки (`PENDING_DELIVERY`, срок = отметка + 24 часа), зачисление сразу, затем уведомление с кнопкой «Оспорить поступление» | `OwnerManualCardApprovalWorkflow.java:127-139`; `SpecialistManualPaymentReviewService.java:43,125-146,361-389` |
| 11 | Кандидат без типа получателя → прямое зачисление | `OwnerManualCardApprovalWorkflow.java:140-145` |
| 12 | Зачисление: только полная сумма заказа; при включённом учёте получатель обязателен | `ManualCardPaymentWorkflow.java:395-401` |
| 13 | Открытая банковская ссылка: наблюдение банка, при необходимости `Cancel` T-Bank с повторной проверкой под блокировкой заказа | `ManualCardPaymentWorkflow.java:446-476,480-505` |
| 14 | Создаётся ссылка-доказательство, заказ переводится в оплату, пишется поступление, зачёт в задание, отметка зачисления проверки | `ManualCardPaymentWorkflow.java:814-841` |
| 15 | Ручная ссылка (без банка): то же без обращения к банку | `ManualCardPaymentWorkflow.java:846-903` |
| 16 | Планировщик раз в 60 с: банк подтвердил ту же ссылку → проверка снимается; повтор зачисления и доставки; по истечении срока без спора → `CONFIRMED`, `NO_OBJECTION_24H` | `SpecialistManualPaymentReviewScheduler.java:24-68`; `SpecialistManualPaymentReviewSettlementService.java:140-170` |
| 17 | Спор: получатель кнопкой в своей рабочей группе либо менеджер через `POST /api/orders/{id}/manual-payment-review/dispute` (пометка «получатель сообщил», текст 10–500) | `SpecialistManualPaymentReviewService.java:497-535,576-600`; `ManagerManualPaymentReviewController.java:23-30` |
| 18 | Спор ставит `DISPUTED`, создаёт задачу сверки; учтённые деньги сами не отменяются | `SpecialistManualPaymentReviewService.java:602-614` |
| 19 | Разбор администратором (ADMIN/OWNER): `verify-received`, `mark-absent`, `reconcile`, `retry-notification` | `SpecialistManualPaymentReviewAdminController.java:39-76` |
| 20 | `mark-absent` по уже зачисленной отметке только регистрирует спор; по незачисленной — `SUPERSEDED`, `PAYMENT_ABSENT` | `SpecialistManualPaymentReviewService.java:309-336` |
| 21 | Прямое подтверждение OWNER/ADMIN по ссылке: нужны «выписка проверена», «перевод поступил», точная сумма, заметка | `AdminPaymentController.java:90-115`; `ManualCardPaymentWorkflow.java:311-326` |
| 22 | Старое подтверждение ручной ссылки запрещено для реквизитов сотрудника, заданий и при включённом учёте получателей | `ManualPaymentConfirmationWorkflow.java:292-297` |
| 23 | Один перевод на несколько заказов: `POST /api/manager/manual-transfer-receipts`, получатель только специалист или менеджер, сумма строк равна переводу | `ManualTransferReceiptController.java:17-22`; `ManualTransferReceiptService.java:40-48,164-167` |

Окно спора: срок отсчитывается от отметки менеджера, а не от доставки; доставка позже 5 минут переводит отметку в `NEEDS_ATTENTION`: `SpecialistManualPaymentReviewService.java:44,138-140,409-412,467-471`; правило «спор разрешён» — `ManualPaymentReviewView.java:15-20`.

## Перерасчёт резервов при другом получателе

Ядро — `recordFinalAttributionsInternal`, вызывается из `recordFinalAttributions` (общий режим) и `recordFinalAttributionsForFrozenSource` (сохранённый режим): `ContractorActualPaymentAttributionService.java:154-192`.

| Шаг | Что происходит | Где |
|---|---|---|
| 1 | Режим: сохранённый для выданного источника, иначе текущий включённый; учёт выключен → 409 | `ContractorActualPaymentAttributionService.java:195-200,116-123` |
| 2 | Повтор: все ключи уже записаны → только проверка без записи; записана часть → 409; строка исправлена администратором → 409 | `ContractorActualPaymentAttributionService.java:210-212,247-259` |
| 3 | Блокировка профилей по возрастанию id, затем распределений источника | `ContractorActualPaymentAttributionService.java:223-240` |
| 4 | `reallocate` = хотя бы одна строка идёт владельцу, без типа или не в профиль исходного распределения | `ContractorActualPaymentAttributionService.java:272-276` |
| 5 | `releaseHistorical`: прочие невыбранные распределения источника → `CANCELED`, причина «Исторический маршрут закрыт при фиксации фактического получателя» | `ContractorActualPaymentAttributionService.java:277-279,1370-1416` |
| 6 | `releaseOriginal` (только при `reallocate`): исходный резерв → `CANCELED`, причина «Фактический получатель ручной оплаты отличается от исходного маршрута»; не удалось → 409 | `ContractorActualPaymentAttributionService.java:280-282,1353-1368`; `ContractorPaymentAccountingService.java:223-253` |
| 7 | `capacity`: обычный доступный остаток профиля; если исходный резерв того же профиля используется повторно — плюс его невыбранная часть | `ContractorActualPaymentAttributionService.java:290-292,1338-1351` |
| 8 | Строка сохраняется с `availableBefore` и `projectedOverrun = max(0, сумма − доступно)`; отклонения нет | `ContractorActualPaymentAttributionService.java:293-331` |
| 9 | `applyNew`: владельцу учёт сотрудников не меняется; тот же профиль без перераспределения → подтверждение на исходном резерве | `ContractorActualPaymentAttributionService.java:1463-1479` |
| 10 | `confirmActualAllocation`: новое распределение `ACTUAL_PAYMENT` (источник — id строки поступления), статус `RESERVED`, резерв, затем подтверждение на сумму строки | `ContractorActualPaymentAttributionService.java:1561-1602,1538-1559` |
| 11 | Распределения источника помечаются сверенными; ждёт уточнения возврата → 409; идёт фоновая сверка → 503 | `ContractorActualPaymentAttributionService.java:335,2118-2142` |
| 12 | Аудит `MANUAL_PAYMENT_ACTUAL_RECIPIENT_RECORDED` | `ContractorActualPaymentAttributionService.java:2034-2077` |

**Платёжное задание** (`settleTaskReceipt`). В задание зачитывается сумма строк с ключом задания; чужой ключ `TASK:` → устаревший маршрут; если в задание ничего не пришло, ключ операции `SPLIT:<idempotencyKey>` и сумма 0: `CommonManualPaymentAttributionCoordinator.java:920-937`. Сумма не больше зарезервированной, ключ совпадает с выданным: `ManualPaymentTaskReceiptIntegrationService.java:438-465`. После закрытия счёта задание завершается при достижении цели: `CommonInvoiceSettlementService.java:1706`. Для счёта одного заказа — `ManualCardPaymentWorkflow.java:835-837,905-911`.

**Режимы учёта**

| Состояние | Правило | Где |
|---|---|---|
| Фаза | Только `SHADOW` или `LIVE`, читается под блокировкой | `ContractorPaymentAccountingPhaseService.java:27-30` |
| Учёт включён | `LIVE` — всегда; `SHADOW` — если настройка `contractor-payments.shadow-enabled` задана и не `false`; ошибка чтения считается «включён» | `ContractorActualPaymentAttributionService.java:125-148` |
| Учёт выключен | Работают только старые подтверждения | `ContractorActualPaymentAttributionFlowPolicy.java:51-62` |
| Выданный маршрут задания общего счёта | Режим из `paymentRouteManualTaskAccountingMode`; пусто и маршрут выбран до включения учёта → `SHADOW`; иначе 409 | `CommonManualPaymentAttributionCoordinator.java:290-324` |
| Ссылка одного заказа | Режим фиксируется вместе с выбором получателя до обращения к банку | `ContractorActualPaymentAttributionService.java:563-617,629-684` |
| Сохранённый режим ≠ записанному | 409 «Режим учёта выданного платежа изменился» | `ContractorActualPaymentAttributionService.java:213-216` |

## Сводка «Кому зачтена оплата» за месяц

- Где: сайт и приложение, раздел «Банк» (`admin/bank-payments`, ADMIN/OWNER), таблица «Кому зачтена оплата»;
  `GET` сводки — `AdminPaymentController.manualRecipientMonthlySummary` → `ManualPaymentRecipientMonthlySummaryService.summary(month)`.
- Сводка только читает; в учёте она ничего не меняет и пересчитывается при каждом открытии, в том числе за прошлые месяцы.

| Источник строки | Кому зачтено |
|---|---|
| Строка поступления (`contractor_actual_payment_attributions`) | Фактический получатель: профиль специалиста или менеджера, владелец, задание на перевод |
| Ручная оплата без строки поступления (старые оплаты) | «Получатель не указан (старые оплаты без атрибуции)», `attributionKnown=false` |
| Оплата по «Счёту и акту Точки» (`payment_method = OWNER_PAPER_INVOICE`), общий счёт или отдельный заказ | Владелец, связанный с менеджером: «Владелец · ФИО (счёт и акт Точки)», ключ `OWNER:PAPER_INVOICE:<id пользователя>`, `attributionKnown=true` (с 2026-11-12) |

У оплаты по «Счёту и акту» строки поступления нет: резерв под неё не создаётся, деньги приходят на расчётный счёт
владельца. Раньше такие общие счета попадали в «Получатель не указан», а оплаченные так отдельные заказы в сводку не
попадали вовсе. Владельца система определяет сама: менеджер общего счёта (`common_billing_accounts.manager_id`, при его
отсутствии — менеджер заказов счёта) или менеджер заказа → пользователь с ролью `ROLE_OWNER` из `managers_users` этого
менеджера. Если у менеджера владельца нет, строка называется «Владелец (счёт и акт Точки)». Запросы:
`CommonInvoiceRepository.findPaperInvoiceOwnersForMonthlyRecipientSummary`,
`PaymentLinkRepository.findPaperInvoiceReceiptsForMonthlyRecipientSummary` (читают и архив). Тесты:
`ManualPaymentRecipientMonthlySummaryServiceTest`, `ManualPaymentRecipientMonthlyArchiveMySqlIntegrationTest`.

## Исправление ошибочного зачисления

- Таблица `manual_receipt_corrections`: одна строка на исходное поступление (`original_id` — первичный ключ), `replacement_id`, `action`, `actor`, `reason`: `MIG/V1_10_394__manual_receipt_corrections.sql:1-12`.
- Кто: только ADMIN — контроллер и сервис: `ManualReceiptCorrectionController.java:13-14`, `ManualReceiptCorrections.java:22`.
- Где в интерфейсе: сайт, страница «Платежи, возвраты и чеки» (`admin/bank-payments`, ADMIN/OWNER), блок «Исправить ручное поступление» виден только ADMIN: `FE/app.routes.ts:370-378`, `FE/features/admin/tbank-payments/tbank-payments.component.html:5,118`, `FE/features/admin/tbank-payments/manual-receipt-correction.component.html:1-3`. В `mobile/src` вызовов `manual-receipts` нет.
- API: `GET /api/admin/payments/manual-receipts?target=&invoice=`, `GET …/recipients`, `POST …/{id}/correction`: `ManualReceiptCorrectionController.java:18-29`.

| Действие | Что делает | Где |
|---|---|---|
| Проверка запроса | `REASSIGN` — ровно одно из «владелец» / профиль; `VOID` — без получателя; причина обязательна | `ManualReceiptCorrections.java:25-26,67-69` |
| Блокировки | Заказы и общий счёт либо заказ, затем строка исправления и строки поступлений источника | `ManualReceiptCorrections.java:72-90` |
| Повтор | Тот же ключ и данные → без изменений; иное → 409 «Поступление уже исправлено» | `ManualReceiptCorrections.java:76-82` |
| `REASSIGN` на владельца | Запрещено для общего счёта с банковским маршрутом и для заказа с отдельной ссылкой-доказательством | `ManualReceiptCorrections.java:30-35,96-98` |
| Сторно | Зачёт в задание отменяется; у исходного получателя подтверждённая сумма возвращается через `recordReturnTotal` | `ContractorActualPaymentAttributionService.java:1425-1444` |
| Замена | Новая строка поступления со ссылкой на исходную (`correction_of_id`), зачисление новому получателю с расчётом превышения | `ContractorActualPaymentAttributionService.java:1446-1460` |
| `VOID` общего счёта | Сторнируются все строки того же подтверждения; заказы и счёт открываются заново: позиции неоплачены, маршрут очищен, статус `READY` или `INVOICED` | `ManualReceiptCorrections.java:93,115`; `CommonInvoiceReceiptCorrection.java:25-63` |
| `VOID` заказа | Ссылка-доказательство `CANCELED`, чек отключён, начисления деактивированы, заказ в статус напоминания, счёт клиенту планируется заново | `ManualReceiptCorrections.java:116-123`; `OrderPaymentCancellationService.java:85-91,103-119,169-190` |
| Проверка менеджера | Зачисленная отметка 24 часов → `SUPERSEDED`, `RECEIPT_CORRECTED` (только заказ) | `ManualReceiptCorrections.java:110-114` |

Уведомление новому получателю после исправления outbox не ставит: выборка исключает строки с `correction_of_id`, а `ManualReceiptCorrections` в outbox не пишет: `CommonInvoicePaymentNotificationOutboxRepository.java:77`.

## Уведомления и спор получателя

**Outbox `common_invoice_payment_notification_outbox`** (`MIG/V1_10_273__common_invoice_payment_notification_outbox.sql:5-63`)

| Вид | Когда ставится | Условия | Где |
|---|---|---|---|
| `CLIENT` | При любом закрытии счёта оплатой, одна запись на счёт | `INSERT IGNORE`, ключ `CLIENT` | `CommonInvoiceSettlementService.java:1704`; `CommonInvoicePaymentNotificationOutboxRepository.java:23-35` |
| `RECIPIENT` | Только при ручной отметке, после записи строк поступления | Режим `LIVE`, тип OWNER/MANAGER/SPECIALIST, пользователь задан, сумма > 0, не замена | `CommonInvoiceSettlementService.java:1732-1739`; `CommonInvoicePaymentNotificationOutboxRepository.java:37-79` |

Строка владельца пишется без профиля и без пользователя, поэтому условие «пользователь задан» её не пропускает: `ContractorActualPaymentAttributionService.java:286-309`.

- Доставка: каждые 15 с, пачка 50, захват с арендой, отметка «отправлено» только своим токеном: `CommonInvoicePaymentNotificationDeliveryService.java:21,28-60`, `CommonInvoicePaymentNotificationOutboxRepository.java:130-218`.
- Клиенту: пропуск, если уже уведомлён или счёт не `PAID`; при выключенных моментальных сообщениях — ошибка и повтор позже: `CommonInvoiceDeliveryService.java:686-722`.
- Получателю: специалисту — в его рабочую группу, менеджеру — в аудит-группу, иначе личный чат; нет чата → ошибка `recipient_telegram_chat_missing`, запись остаётся на повтор: `ManualPaymentRecipientTelegramNotificationService.java:120-175,210-221`.
- Текст: «💳 Оплата по реквизитам подтверждена», получатель, сумма, «Общий счёт №…», число заказов, кто подтвердил, время, «Зачтено в расчёт получателя.»: `ManualPaymentRecipientTelegramNotificationService.java:246-271`.
- Кнопка спора (добавлена сегодня): при доставке из outbox к тексту добавляется абзац про 24 часа и кнопка «⚠️ Оспорить поступление» с данными `common-payment-dispute:<deliveryId>`: `ManualPaymentRecipientTelegramNotificationService.java:151-159`, `CommonInvoicePaymentNotificationDeliveryService.java:106-117`.
- Счёт одного заказа: уведомление получателю после коммита, без кнопки спора; при отметке через проверку 24 часов оно не шлётся (там своё сообщение с кнопкой): `ManualPaymentRecipientTelegramNotificationService.java:45-113,223-244`, `ManualCardPaymentWorkflow.java:834,895`.

**Спор по общему счёту** (`CommonInvoicePaymentDisputeService.dispute`)

| Проверка | Результат | Где |
|---|---|---|
| Уведомление найдено и у него есть получатель | Иначе 404 | `CommonInvoicePaymentDisputeService.java:35-38` |
| Нажал сам получатель (его Telegram id) | Иначе 403 «Оспорить поступление может только указанный получатель» | `CommonInvoicePaymentDisputeService.java:39-44` |
| Нажато в личном чате или в рабочей группе получателя | Иначе 403 | `CommonInvoicePaymentDisputeService.java:45-53` |
| Уже оспорено | Ответ «Поступление уже оспорено…», новой задачи нет | `CommonInvoicePaymentDisputeService.java:54-56,61-63` |
| Не позже 24 часов от `sent_at` | Иначе 409 «Срок спора (24 часа) прошёл…» | `CommonInvoicePaymentDisputeService.java:27,57-60` |
| Первая отметка | `disputed_at`, `disputed_by_telegram_user_id` на строке outbox | `CommonInvoicePaymentNotificationOutboxRepository.java:118-128`; `MIG/V1_10_395__common_invoice_recipient_dispute.sql:1-5` |
| Задача | `COMMON_INVOICE_PAYMENT_DISPUTE` по первому заказу счёта: ответственному менеджеру, владельцам, администраторам | `CommonInvoicePaymentDisputeService.java:64-74`; `PaymentIssueReminderService.java:124-155,206-222` |
| Деньги | Не отменяются; текст задачи отправляет в «Платежи, возвраты и чеки» | `CommonInvoicePaymentDisputeService.java:71-73` |

Кнопки разбирает один обработчик: сначала префикс `common-payment-dispute:`, затем `specialist-payment-dispute:`; отказ сервиса показывается нажавшему: `SpecialistManualPaymentReviewTelegramCallbackService.java:15,21-43,46-61`; вызов — `TelegramService.java:615-625`.

**Владельцы и администраторы** (`ManualCardPaymentReviewNotificationService`)

| Событие | Что уходит | Где |
|---|---|---|
| Отчёт владельцу по общему счёту | Каждому активному OWNER и ADMIN: одно напоминание (источник `COMMON_INVOICE_OWNER_RECEIPT_APPROVAL`, id отчёта) и личный Telegram с подсказкой, где решать | `ManualCardPaymentReviewNotificationService.java:62-68,225-232,250-284,334-338` |
| Решение принято (принят или отклонён) | Незакрытые напоминания по отчёту удаляются | `ManualCardPaymentReviewNotificationService.java:70-78`; `CommonInvoiceManualPaymentWorkflow.java:416,424` |
| Отклонение | Только автору отчёта: напоминание `COMMON_INVOICE_OWNER_RECEIPT_REJECTED` и Telegram «Счёт остался неоплаченным…» | `ManualCardPaymentReviewNotificationService.java:80-86,234-247` |
| Запрос владельцу по заказу | Кнопка в группе менеджера и задача сверки по заказу | `ManualCardPaymentReviewNotificationService.java:299-330` |
| Старая ручная отметка общего счёта (без учёта получателей) | Напоминание и Telegram владельцам и администраторам, задача по первому заказу | `ManualCardPaymentReviewNotificationService.java:170-223`; `CommonInvoiceManualPaymentWorkflow.java:754-757` |

Все эти уведомления выполняются после коммита в новой транзакции; сбой только пишется в журнал: `ManualCardPaymentReviewNotificationService.java:97-121`. `PaymentIssueReminderService` создаёт по одной системной карточке на получателя и закрывает предупреждения оплаченной позиции общего счёта: `PaymentIssueReminderService.java:145-154,187-197`, `CommonInvoiceSettlementService.java:1707,1713-1730`.

## Права

| Действие | Кто может | Где |
|---|---|---|
| Открыть окно и отметить общий счёт | ADMIN, OWNER, MANAGER | `CommonManualPaymentAttributionController.java:32` |
| Видеть счёт | ADMIN — все; OWNER — счета своих менеджеров; MANAGER — только свои | `CommonInvoiceDeliveryService.java:780-806` |
| Счёт, чьё распределение принадлежит ADMIN/OWNER | Владельцу без настройки `otziv.security.owner-manage-privileged-users` — 404 «Общий счет не найден»; ADMIN — всегда можно | `ContractorPaymentTargetAccessPolicy.java:33,87-96,118-122` |
| Кандидаты и история | Показываются только те, кем пользователь может управлять | `CommonManualPaymentAttributionCoordinator.java:718-722,807-821` |
| Зачислить владельцу, принять отчёт | Только OWNER/ADMIN (`canDecide`) | `CommonOwnerReceiptApprovals.java:45-47,117-119` |
| Отклонить отчёт | Только OWNER/ADMIN | `CommonOwnerReceiptDecisionController.java:21`; `CommonOwnerReceiptApprovals.java:124-128` |
| Отметить заказ переводом | ADMIN, OWNER, MANAGER с доступом к заказу | `AdminPaymentController.java:117,129`; `ManualCardPaymentWorkflow.java:137,214,406` |
| Подтвердить ссылку по выписке, разбор отметок 24 часов, закрыть «не оплачено» | ADMIN, OWNER | `AdminPaymentController.java:90,168,201`; `SpecialistManualPaymentReviewAdminController.java:27-65` |
| Подтвердить поступление владельцу по заказу | Активный OWNER/ADMIN в группе менеджера компании | `OwnerManualCardApprovalWorkflow.java:273-279,318-325` |
| Исправить или отменить зачисление | Только ADMIN | `ManualReceiptCorrections.java:22` |
| Возврат по заданию общего счёта | ADMIN, OWNER | `CommonManualPaymentTaskReturnController.java:26-27` |

Менеджер не может: зачислить владельцу, принять или отклонить отчёт владельцу, подтвердить владельца на банковском маршруте, разобрать спор на стороне администратора, исправить зачисление.

## Сценарии — таблица: условие → что делает система → сообщение/результат → где в коде → каким тестом покрыто (или «нет теста»)

Тесты лежат в `TEST/common_billing/service/`, `TEST/common_billing/controller/`, `TEST/contractor_payments/service/`, `TEST/payments/`, `TEST/payments/service/`, `TEST/p_products/payment/service/`; спецификации сайта — рядом с компонентами, тесты приложения — `mobile/test/`.

| Условие | Что делает система | Сообщение / результат | Где | Тест |
|---|---|---|---|---|
| Счёт заказа — ссылка банка, клиент перевёл владельцу на карту, профиль «карта до лимита» | Владелец есть в списке; отметка менеджера → запрос `PENDING`, заказ и ссылка не меняются; после кнопки владельца в Telegram заказ оплачен, ссылка закрыта, поступление записано на владельца, сумма в месячном лимите карты | `OWNER_APPROVAL_PENDING` → «Поступление владельцу подтверждено» | `ManualCardRoutePolicy`, `OwnerManualCardApprovalWorkflow`, `ManualCardPaymentWorkflow.manualCardPaymentEvidenceLink` | `PaymentScenarioMatrixMySqlIntegrationTest#s21_transferToTheOwnersCardPastTheBankLinkIsConfirmedByTheOwner`; окно — `manager-manual-card-payment-modal.component.spec.ts` «offers the owner on a bank link only as a request…» |
| То же, профиль «только банк» (владелец-ИП) | Владельца нет в кандидатах; запрос с владельцем отклоняется, записи запроса не создаётся | 409 «Оплату владельцу по банковской ссылке подтверждает банк» | те же | тот же сценарий 21 (первая часть); окно — «explains why the owner is absent…» |
| Webhook T-Bank `CONFIRMED` по ссылке заказа | Закрывает заказ оплатой | Ссылка `CONFIRMED` | `PaymentLinkSettlementService.java:330-341` | `PaymentLinkServiceTest#confirmedWebhookAppliesOrderPaymentTransitionOnlyWhenEnabled` |
| То же, заказ не выполнен | Предоплата | Заказ не закрыт | `PaymentLinkSettlementService.java:325-329` | `PaymentLinkServiceTest#confirmedWebhookBeforeOrderCompletionStoresPrepaymentWithoutClosingOrder` |
| То же, сумма устарела | Не оплачивает | `AMOUNT_MISMATCH` | `PaymentLinkSettlementService.java:308-310` | `PaymentLinkServiceTest#confirmedWebhookWithStaleAmountDoesNotMarkOrderPaid` |
| Webhook Точки по ссылке заказа | Проверка JWT до поиска, подтверждение | Идемпотентно | `BankPaymentReconciliationWorkflow.java:160-230` | `PaymentLinkServiceTest#exactApprovedTochkaWebhookConfirmsIdempotentlyAfterPaymentSwitchesAreDisabled` |
| Фоновая сверка ссылок | Обходит всех кандидатов | — | `PaymentBankStatusReconciliationService.java:40-100` | `PaymentBankStatusReconciliationServiceTest#checksEveryStaleBankPaymentCandidate` |
| Webhook `CONFIRMED` общего счёта, не все заказы готовы | Предоплата | Заказы не закрыты | `CommonInvoiceSettlementService.java:974-977` | `CommonBillingServiceTest#confirmedCurrentWebhookBeforeAllOrdersReadyStoresPrepaymentWithoutClosingOrders` |
| Подписанное, но противоречивое подтверждение T-Bank | Счёт не оплачивается | Ошибка | `CommonInvoiceSettlementService.java:885-894` | `CommonBillingServiceTest#inconsistentSignedTbankConfirmationCannotPayCommonInvoice` |
| Первое точное поступление по заменённой ссылке Точки | Закрывает счёт один раз | `PAID` | `CommonInvoiceSettlementService.java:590-636` | `CommonBillingServiceTest#exactFirstReceiptOnReplacedTochkaLinkSettlesOnce` |
| Ручная отметка, открыта форма T-Bank | `GetState` → `Cancel` → зачисление получателю | Счёт `PAID`, outbox | `CommonInvoiceManualPaymentWorkflow.java:529-578` | `CommonBillingServiceTest#attributedMarkPaidCancelsUnpaidTbankSessionBeforeCreditingActualRecipient` |
| Ручная отметка, T-Bank сообщает движение денег | Стоп, счёт `NEEDS_ATTENTION` | 409 «…нужна сверка двух оплат» | `CommonInvoiceManualPaymentWorkflow.java:599-602,642-653` | `CommonBillingServiceTest#attributedMarkPaidStopsWhenTbankReportsFinancialMovement` |
| Во время сверки пришёл webhook `CANCELED` | Продолжает зачисление | `PAID` | `CommonInvoiceManualPaymentWorkflow.java:668-677` | `CommonBillingServiceTest#attributedMarkPaidAcceptsConcurrentCanceledWebhookBeforeCreditingActualRecipient` |
| Активная попытка Точки при сверке через T-Bank | Отказ, T-Bank не вызывается | 409 «…сначала завершите сверку попытки Точки» | `CommonInvoiceManualPaymentWorkflow.java:502-511` | `CommonBillingServiceTest#activeTochkaAttemptNeverUsesTbankLookupForManualCardReconciliation` |
| Точка не подтвердила «не оплачено» при отметке | Отказ | 409 «Точка ещё не подтвердила отсутствие оплаты…» | `CommonInvoiceManualPaymentWorkflow.java:256-260` | нет теста |
| Запись поступления упала | Откат, побочных эффектов нет | Исключение | `CommonInvoiceSettlementService.java:1700-1704` | `CommonBillingServiceTest#finalAttributionFailureStopsSuccessSideEffects` |
| Старое `/paid` при реквизитах сотрудника (LIVE) | Отказ | 409 | `CommonInvoiceManualPaymentWorkflow.java:912-918` | `CommonBillingServiceTest#genericMarkPaidRejectsFrozenLiveContractorCommonSource` |
| Сумма строк меньше или больше остатка | Отказ | 400 «Сумма по получателям должна быть равна остатку общего счета» | `CommonManualPaymentAttributionCoordinator.java:392-394` | нет теста на сервере; `FE/features/admin/common-billing/common-manual-payment-attribution-modal.component.spec.ts:27` проверяет сумму строк |
| Разбиение на двух получателей | Две строки, сумма равна остатку | Счёт `PAID` | `CommonManualPaymentAttributionCoordinator.java:349-391` | `CommonManualPaymentAttributionCoordinatorTest#acceptsExactRecordedBatchAndVerifiesAccountingInvariant` (повтор пакета) |
| «Другие получатели» запрошены | Команда и исполнители компаний вне обычного списка | `previousPerformer` / `teamMember` | `CommonManualPaymentAttributionCoordinator.java:140-154` | `CommonManualPaymentAttributionCoordinatorTest#otherRecipientsOfferWholeTeamAndCompanyPerformersOutsideOrdinaryOptions` |
| В запросе назван разовый получатель | Подгружается только он | Зачисление ему | `CommonManualPaymentAttributionCoordinator.java:667-669,1071-1082` | `CommonManualPaymentAttributionCoordinatorTest#finalReceiptAcceptsNamedOneOffRecipientWithoutLoadingWholeTeam` |
| Профиль вне команды и исполнителей | Отказ | 409 `PAYMENT_ROUTE_STALE` | `CommonManualPaymentAttributionCoordinator.java:360-362` | `CommonManualPaymentAttributionCoordinatorTest#finalReceiptRejectsProfileOutsideTeamAndCompanyPerformers` |
| Задание и тот же профиль напрямую | Отказ | 400 | `CommonManualPaymentAttributionCoordinator.java:422-449` | `CommonManualPaymentAttributionCoordinatorTest#rejectsTaskAndDirectRowsForTheSameEconomicProfile` |
| Повтор с частью записанного пакета | Отказ | 409 «Ключ повтора частично использован…» | `CommonManualPaymentAttributionCoordinator.java:218-221` | `CommonManualPaymentAttributionCoordinatorTest#rejectsReplayThatContainsOnlyPartOfOriginallyRecordedBatch` |
| Учёт выключен, маршрут задания выдан | Новый поток доступен, режим сохранённый | 200 | `CommonManualPaymentAttributionController.java:52-53` | `CommonManualPaymentAttributionControllerTest#frozenTaskRouteKeepsTypedEndpointAvailableWhenGlobalFlagIsOff`; `CommonManualPaymentAttributionCoordinatorTest#frozenTaskReceiptUsesPersistedModeAfterGlobalFeatureDisable` |
| Менеджер указал владельца | Отчёт, счёт не закрыт | Окно «Ожидает подтверждения владельца» | `CommonOwnerReceiptApprovals.java:75-106` | сервер — нет теста; сайт — `…attribution-modal.component.spec.ts:96` |
| Владелец отклонил отчёт | Отчёт `REJECTED`, автору уведомление | «Владелец не подтвердил оплату общего счёта №…» | `CommonInvoiceManualPaymentWorkflow.java:419-435` | `ManualCardPaymentReviewNotificationServiceTest#ownerDecisionClosesItsRemindersAndRejectionReachesOnlyTheReportingManager` (только уведомления) |
| Новый отчёт владельцу | Напоминание и Telegram OWNER/ADMIN один раз | «Подтвердить поступление владельцу по общему счёту №…» | `ManualCardPaymentReviewNotificationService.java:225-232` | `ManualCardPaymentReviewNotificationServiceTest#commonInvoiceOwnerReportRemindsOwnersAndAdminsOnceAndTellsWhereToDecide` |
| Получатель — исходный профиль | Подтверждение на исходном резерве | Нового распределения нет | `ContractorActualPaymentAttributionService.java:1473-1477` | `ContractorActualPaymentAttributionServiceTest#confirmsOriginalRecipientWithoutCreatingSecondAllocation` |
| Специалист → менеджер | Исходный резерв `CANCELED`, новое `ACTUAL_PAYMENT` | — | `ContractorActualPaymentAttributionService.java:280-282,1561-1602` | `ContractorActualPaymentAttributionServiceTest#reallocatesSpecialistToManagerAtomically` |
| Специалист → владелец | Резерв снят, сотрудникам ничего не зачислено | — | `ContractorActualPaymentAttributionService.java:1470-1472` | `ContractorActualPaymentAttributionServiceTest#reallocatesSpecialistToOwnerWithoutContractorCredit` |
| Сумма больше доступного | Платёж учтён, превышение записано | `projectedOverrunKopecks > 0` | `ContractorActualPaymentAttributionService.java:314-315` | `ContractorActualPaymentAttributionServiceTest#ownerRouteCreditsActualManagerAndRecordsOverrun` |
| Освобождение резерва не удалось | Стоп до записи строки | 409 | `ContractorActualPaymentAttributionService.java:1364-1366` | `ContractorActualPaymentAttributionServiceTest#failedReleaseStopsBeforeImmutableRowOrActualCredit` |
| Сохранённый `SHADOW`, глобально уже `LIVE` | Завершает в `SHADOW` | — | `ContractorActualPaymentAttributionService.java:168-176` | `ContractorActualPaymentAttributionServiceTest#frozenCommonSourceKeepsPersistedShadowModeAfterGlobalPromotionAndReplays` |
| Отметка заказа менеджером на специалиста | Зачисление сразу, затем уведомление | Статус `COMPLETED`, «Оплата учтена сразу…» (`ManagerManualCardPaymentResultResponse.java:14-19`) | `OwnerManualCardApprovalWorkflow.java:127-139` | `SpecialistManualPaymentReviewSettlementServiceTest#managerMarkCreditsImmediatelyBeforeNotificationForEveryRecipientAndNeverTwice` |
| Отметка заказа без выбора получателя при включённом учёте | Отказ | 409 `ACTUAL_RECIPIENT_REQUIRED` (`ManualPaymentTaskRouteErrors.java:16-28`) | `ManualCardPaymentWorkflow.java:395-398` | `PaymentLinkServiceTest#managerManualCardReportRequiresExplicitRecipientWhenActualRecipientAccountingIsEnabled` |
| Сумма перевода ≠ сумме заказа | Отказ до обращения к банку | 409 «Сумма перевода не равна полной сумме заказа…» | `ManualCardPaymentWorkflow.java:399-401` | `PaymentLinkServiceTest#manualCardPaymentRequiresExactAmountBeforeCallingProvider` |
| Кнопку владельца нажал менеджер или не та группа | Зачисления нет | 403 | `OwnerManualCardApprovalWorkflow.java:273-279,318-325` | `OwnerManualCardApprovalWorkflowMySqlIntegrationTest#wrongActorAndWrongGroupNeverDispatchTheManualPayment` |
| Спор специалиста в своей группе до срока | `DISPUTED`, задача | «Спор сохранён…» | `SpecialistManualPaymentReviewService.java:512-534` | `SpecialistManualPaymentReviewServiceTest#onlyAssignedSpecialistCanDisputeInTheirGroupBeforeDeadline` |
| `mark-absent` по зачисленной отметке | Только спор, деньги не трогаются | — | `SpecialistManualPaymentReviewService.java:309-314` | `SpecialistManualPaymentReviewServiceTest#creditedAbsenceRegistersDisputeWithoutPretendingToRefundOrCancelMoney` |
| Доставка уведомления получателю общего счёта | Отправка, запись закрывается один раз | — | `CommonInvoicePaymentNotificationDeliveryService.java:94-129` | `CommonInvoicePaymentNotificationDeliveryServiceTest#deliversRecipientAndFinalizesTheClaimOnce` |
| Доставка из outbox | Кнопка `common-payment-dispute:<deliveryId>` | — | `ManualPaymentRecipientTelegramNotificationService.java:151-159` | `ManualPaymentRecipientTelegramNotificationServiceTest#outboxDeliveryOfCommonInvoiceReceiptCarriesDisputeButtonBoundToThatDelivery` |
| Нет рабочей группы получателя | В личный чат не шлёт, повтор | `recipient_telegram_chat_missing` | `ManualPaymentRecipientTelegramNotificationService.java:143-149` | `ManualPaymentRecipientTelegramNotificationServiceTest#missingOrInvalidGroupNeverFallsBackToPersonalChatForOrdinaryOrCommonInvoice` |
| Спор получателя общего счёта | Одна отметка, одна задача | «Спор сохранён…» | `CommonInvoicePaymentDisputeService.java:61-75` | `CommonInvoicePaymentDisputeServiceTest#recipientDisputesOnceInOwnGroupAndStaffGetOneTask` |
| Повторное нажатие | Без новой задачи | «Поступление уже оспорено…» | `CommonInvoicePaymentDisputeService.java:54-56` | `CommonInvoicePaymentDisputeServiceTest#repeatedPressNeitherMarksNorAlarmsAgain` |
| Нажал другой участник группы | Отказ | 403 | `CommonInvoicePaymentDisputeService.java:41-44` | `CommonInvoicePaymentDisputeServiceTest#anotherMemberOfTheGroupCannotDispute` |
| Прошло 24 часа от доставки | Отказ | 409 «Срок спора (24 часа) прошёл…» | `CommonInvoicePaymentDisputeService.java:57-60` | `CommonInvoicePaymentDisputeServiceTest#windowClosesDayAfterDelivery` |
| Кнопка общего счёта | Уходит в свой сервис, не в отметку заказа | — | `SpecialistManualPaymentReviewTelegramCallbackService.java:22-24` | `SpecialistManualPaymentReviewTelegramCallbackServiceTest#commonInvoiceReceiptButtonGoesToItsOwnDisputeAndNeverToTheOrderReview` |
| Владелец без права на ADMIN/OWNER открывает счёт с таким получателем | Счёт скрыт | 404 | `ContractorPaymentTargetAccessPolicy.java:87-96` | `ContractorPaymentTargetAccessPolicyTest#commonInvoiceMutationConcealsDurablePrivilegedRecipient` |
| Исправление получателя, отмена зачисления общего счёта | Сторно, замена или открытие долга | — | `ManualReceiptCorrections.java:65-124` | нет теста |
| Отмена ручной предоплаты заказа | Ссылка `CANCELED`, начислений нет | Аудит `manual_prepayment_voided` | `OrderPaymentCancellationService.java:136-143` | `OrderPaymentCancellationServiceTest#voidManualPrepaymentKeepsWorkInProgressAndHasNoPayrollToReverse` |
| Сайт: первое открытие счёта с реквизитами | Получатель из счёта выбран, одна кнопка | — | `FE/features/admin/common-billing/common-manual-payment-attribution-modal.component.ts:242-247` | `…attribution-modal.component.spec.ts:70` |
| Сайт: банковский маршрут | Получатель не предвыбран | — | `FE/shared/common-manual-payment-route-refresh.ts:46-53` | `FE/shared/common-manual-payment-route-refresh.spec.ts:40` |
| Приложение: маршрут изменился под окном | Строки и галочки сброшены, причина сохранена | «Маршрут оплаты изменился…» | `MOB/shared/mobile-common-manual-payment-dialog.component.ts:292-300` | `mobile/test/tbank-payment-task-safety.test.mjs:76` |

## Не проверено / открытые вопросы

- Ничего не запускалось: ни тесты, ни стенд. Всё выше — чтение кода; поведение в интерфейсе и фактические строки в БД не подтверждены.
- Фоновый опрос T-Bank для текущей ссылки общего счёта не найден: в `common_billing` `getState` вызывается только при ручной отметке, отмене и смене маршрута (`CommonInvoiceManualPaymentWorkflow.java:540`, `CommonInvoiceCancellationService.java:179`, `CommonInvoicePaymentRouteWorkflow.java:599`). Есть ли иной страхующий путь, кроме webhook, не установлено. `BankRecipientChanges.observeReplacedLinks` и `CommonInvoiceSettledBankReturnReconciliationService` не разбирались.
- Приложение для счёта с банковским маршрутом шлёт `attention/manual-card-paid-with-attributions` (`MOB/features/common-billing.page.ts:802`), а сервер принимает этот путь только при конфликте одиночных платежей (`CommonInvoiceManualPaymentWorkflow.java:770-772`). По коду обычный счёт с банковской ссылкой из приложения так не отметить; на устройстве не проверено.
- В приложении подсказка обещает предвыбранного получателя, а код создаёт пустую строку (см. таблицу окон). Намеренно ли — неизвестно.
- Серверных тестов нет на: отказ при частичной сумме, `CommonOwnerReceiptApprovals` (постановка, принятие, отклонение, отпечаток), `ManualReceiptCorrections`, `CommonInvoiceReceiptCorrection`, ветку Точки в `markPaidWithAttributions`. В `CommonBillingServiceTest` `CommonOwnerReceiptApprovals` заменён заглушкой (`TEST/common_billing/service/CommonBillingServiceTest.java:441-447`).
- Отметка 24 часов с получателем-владельцем (`ownerGroup` в `SpecialistManualPaymentReviewService.java:426-432,501-511`): не найдено, какой путь создаёт такую запись — оба найденных входа отправляют владельца в запрос владельцу либо допускают только специалиста и менеджера (`OwnerManualCardApprovalWorkflow.java:123-126`, `ManualTransferReceiptService.java:164-167`).
- Какой именно кандидат имеет пустой тип получателя и уходит в прямое зачисление (`OwnerManualCardApprovalWorkflow.java:140-145`) — не прослежено.
- Окно отметки счёта одного заказа в приложении не найдено и не разбиралось (`MOB/features/order-details/order-payment.facade.ts` изменён сегодня, не читался).
- Не разбирались: клиентская кнопка «я оплатил» (`PublicPaymentController.java:119`), возврат по заданию (`CommonManualPaymentTaskReturnService`), `ledgerService.settle` и `reverseActualReceipt` внутри `ManualPaymentTaskLedgerService`, сверка суммы возврата `recordReturnTotal`, `SpecialistManualPaymentReviewGuard`.
- Текст клиентского сообщения «оплата получена» (`CommonInvoiceDeliveryService.java:724` и ниже) не читался.
- Уведомление новому получателю после исправления зачисления: постановки в outbox не найдено; есть ли другое оповещение — неизвестно.
