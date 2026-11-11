# Автоответчик и счета
> Для ИИ-агентов. Состояние кода: рабочее дерево main на 2026-11-10.

Документ описывает, что фоновый автоответчик делает с выставленными и неоплаченными счетами: одиночный заказ,
общий счёт (`common_billing`) и бумажный счёт. Все ссылки — от корня репозитория, формат `путь:строка`.

Сокращения путей: `BE/` = `backend/src/main/java/com/hunt/otziv/`, `BT/` = `backend/src/test/java/com/hunt/otziv/`,
`FE/` = `frontend/src/app/`, `MOB/` = `mobile/src/app/`.
`SCMS` = `BE/client_messages/service/ScheduledClientMessageService.java`,
`PIRS` = `BE/client_messages/service/PaymentInvoiceRetryScheduler.java`,
`CMAP` = `BE/client_messages/service/ClientMessageAutomationPolicy.java`,
`OSTS` = `BE/p_products/status/service/OrderStatusTransitionService.java`,
`CIDS` = `BE/common_billing/service/CommonInvoiceDeliveryService.java`,
`CIOE` = `BE/common_billing/service/CommonInvoiceOverdueEscalation.java`,
`CIAW` = `BE/common_billing/service/CommonInvoiceArchiveWorkflow.java`,
`CIMW` = `BE/common_billing/service/CommonInvoiceMessageWorker.java`,
`CIMem` = `BE/common_billing/service/CommonInvoiceMembershipWorkflow.java`,
`CBAC` = `BE/common_billing/controller/CommonBillingAdminController.java`,
`ADC` = `BE/admin/controller/ApiAdminDictionaryController.java`,
`ASS` = `BE/config/settings/service/AppSettingService.java`.
Файлы без каталога в ссылке (`CommonInvoiceOverdueTerm.java` и т. п.) перечислены с полным путём в «Карте кода» или при первом упоминании.

Номера строк сверены 2026-11-10 около 19:35. Во время работы параллельная задача правила
`CommonBillingAdminController.java` и `CommonInvoicePaymentRouteWorkflow.java` (строки сдвинулись на 14 и 40+);
для этих двух файлов при расхождении ищите по имени метода или маршрута.

## Коротко — 8–12 правил, которые нельзя нарушать

1. **Одна очередь для одиночных заказов.** Все фоновые платёжные шаги — строки `scheduled_client_message_state` со сценарием из `ClientMessageScenario` (`BE/client_messages/model/ClientMessageScenario.java:3-15`), исполняет только `tick()` (`SCMS:263-373`). Второй отправитель в обход очереди не создавать.
2. **Разрешение читается свежо и дважды.** `ClientMessageAutomationPolicy.allowed` (worker, пауза, переключатель сценария; ошибка чтения = запрет, `CMAP:21-41`) проверяется при подготовке (`SCMS:2939`) и ещё раз перед вызовом провайдера (`SCMS:2521`, `SCMS:2073-2075`).
3. **Внешняя отправка — только при `client.messages.live.enabled`.** Чтение `getBooleanFreshFailClosed`, ошибка = пауза (`SCMS:2555-2562`, `ASS:46-51`). Выключено → dry-run без отправки (`SCMS:3591-3594`).
4. **Общий бюджет.** Любое фоновое клиентское сообщение проходит блокировку бюджета, дневной лимит, интервал канала, рабочие окна и интервал аккаунта (`SCMS:2859`, `SCMS:2966-2990`, `SCMS:3603-3634`). Отправители вне очереди обязаны вызывать `BackgroundDispatchBudget.reserveForSender` (`SCMS:4315-4371`), как общий счёт (`CIMW:65-80`) и бумажные напоминания (`BE/paper_billing/PaperBillingReminders.java:52-63`).
5. **«Не оплачено» — только через бизнес-логику.** Одиночный заказ: `OrderStatusTransitionService` (создаёт плохие задачи, `OSTS:487-496`). Общий счёт: `moveLockedInvoiceToUnpaid` (`CIAW:197-237`). Прямой SQL-апдейт статуса запрещён по смыслу этих методов.
6. **Автоперевод одиночного заказа по умолчанию выключен.** `client.messages.payment-overdue.live-enabled` = `false`: создаётся только запись `payment_overdue_dry_run` (`SCMS:3487-3505`). Тот же ключ обязателен для общего счёта (`CIOE:40`, `CIOE:83`).
7. **Заказ в активном общем счёте не обслуживается одиночной платёжной автоматикой** (`SCMS:1619-1624`, `SCMS:3326-3341`, `SCMS:3473-3481`); его финансовый статус меняет только общий счёт (`OSTS:415-420`).
8. **Сохранённое сообщение не переотправляется.** Состояния `PREPARED`/`UNKNOWN` блокируют перевзвод (`BE/client_messages/service/ClientMessageStateSafety.java:17-34`); восстановление — только поиск квитанции (`BE/client_messages/service/ScheduledDeliveryRecovery.java:17`, `:60-68`).
9. **Удержания останавливают автоматику:** бумажный счёт (`paperInvoiceAutomationHold`), проверка ручной оплаты (`SpecialistPaymentReviewGate`), `NEEDS_ATTENTION`, восстановление отзывов. Новый путь обязан повторить эти проверки (`SCMS:1447-1451`, `SCMS:2954-2962`, `CIDS:611-616`, `SCMS:3737-3756`).
10. **Маркер смены маршрута хранится в `lastError`.** `payment_route_changed_message_pending` нельзя затирать: по нему планировщик досылает новые реквизиты (`CIDS:74`, `BE/common_billing/repository/CommonInvoiceRepository.java:293-296`).
11. **Общий счёт уходит в «Не оплачено» только после уведомления менеджера** и ещё двух суток (`BE/common_billing/api/CommonInvoiceOverdueNotices.java:15`, `CIOE:238-245`).
12. **Сервер не ограничивает ручной «Не оплачено» ролями OWNER/ADMIN** — ограничение сделано только в интерфейсах (см. «Ручные действия и права»).

## Карта кода — таблица: класс/файл → за что отвечает

| Класс / файл | За что отвечает |
|---|---|
| `SCMS` | Ядро автоответчика: `tick()`, сверка кандидатов, подготовка → отправка → финализация, бюджет, автоархив молчания, `BackgroundDispatchBudget` |
| `PIRS` | Постановка и отмена платёжных задач заказа, защита от смены маршрута и ручной оплаты (`ManualPaymentAutomationControl`) |
| `CMAP` | Единая свежая проверка: worker, пауза, переключатель сценария |
| `BE/client_messages/service/ClientMessageSlotPlanner.java` | Рабочие окна (Иркутск) и интервал: `nextAllowedAt`, `afterGap` |
| `BE/client_messages/service/SenderAccountPacing.java` | Интервал аккаунта-отправителя, общий с кампаниями |
| `BE/client_messages/service/ClientMessageOrderStatusChangedListener.java` | AFTER_COMMIT пересборка задач после смены статуса заказа (`:18-25`) |
| `BE/client_messages/service/ClientMessageStateSafety.java` | Коды и статусы, запрещающие автоматический повтор |
| `BE/client_messages/service/ScheduledDeliveryRecovery.java` | Сверка квитанций для `PREPARED`/`UNKNOWN` без отправки |
| `BE/client_messages/service/ClientMessageOperationFence.java` | Барьер «не более одной неразрешённой отправки» для Telegram/MAX |
| `BE/client_messages/service/ClientMessageMaintenancePreviewService.java` | Админ-актуализация, в т.ч. `applyPaymentOverdue()` |
| `BE/client_messages/service/ClientMessageMonitorService.java` | Монитор очереди: ручной повтор, отключение, закрытие задачи |
| `BE/client_messages/controller/ClientMessageMonitorController.java` | HTTP монитора и актуализации |
| `ASS`, `BE/config/settings/api/BackgroundAutomationSettings.java` | Хранилище настроек (`app_settings`), ключи, свежие чтения |
| `ADC` | `GET/PUT /settings/client-messages` — экран настроек автоответчика (`:823-996`) |
| `OSTS` | Переходы статусов заказа, плохие задачи при «Не оплачено», «Бан» |
| `BE/p_products/application/OrderStatusCommandService.java` | Ролевая проверка ручной смены статуса заказа |
| `BE/bad_reviews/service/BadReviewTaskServiceImpl.java` | Плохие задачи: создание, выполнение, счёт с новой суммой |
| `CIDS` | Общий счёт: подготовка сообщения, напоминания, финализация, маркеры смены маршрута |
| `CIMW`, `BE/common_billing/service/CommonInvoiceMessageQueue.java` | Очередь и воркер сообщений общего счёта |
| `BE/common_billing/service/CommonBillingReminderScheduler.java` | Планировщики напоминаний и неотправленных счетов (`:16-30`) |
| `CIOE`, `BE/common_billing/service/CommonInvoiceOverdueClock.java`, `BE/common_billing/service/CommonInvoiceOverdueTerm.java` | Срок оплаты общего счёта, уведомление, автоперевод, отсрочка |
| `CIAW` | `markUnpaid`, `markUnpaidAfterOverdue`, `markBan`, вердикт `CommonInvoiceUnpaidAttention` |
| `CIMem`, `BE/common_billing/service/CommonInvoiceBadReviewBankRepricing.java` | Пересчёт суммы общего счёта после плохой задачи |
| `BE/common_billing/service/BankPaperInvoiceAdapter.java`, `BE/p_products/service/PaperInvoiceOrderAdapter.java` | Удержания автоматики бумажного счёта (счёт и заказы) |
| `BE/paper_billing/PaperBillingReminders.java` | Напоминания по бумажным комплектам общего счёта |
| `BE/notification_media/service/ThematicStaffNotificationJob.java` | Уведомление менеджера перед автопереводом общего счёта (`:74-121`) |
| `BE/personal_reminders/service/PaymentAttentionReminderJob.java`, `BE/personal_reminders/service/PaymentAttentionReminderService.java` | Личная карточка менеджеру за 2 суток до просрочки одиночного заказа |
| `FE/features/admin/dictionaries/admin-client-message-settings.facade.ts` | Форма настроек автоответчика (`autoresponderForm`) |
| `FE/features/admin/common-billing/common-invoice-overdue-term.component.ts` | Блок «Срок оплаты» общего счёта и отсрочка |

## Настройки автоответчика, влияющие на счета — таблица: поле → смысл → значение по умолчанию → где читается

Хранилище — таблица ключ/значение через `AppSettingService`; кеш процесса 5 минут (`ASS:251-252`), свежие чтения — `getBooleanFreshFailClosed`/`getStringFresh` (`ASS:280-292`). Поле = имя в `autoresponderForm` и DTO (`ADC:2110-2170`).

| Поле (ключ `client.messages.*`) | Смысл | По умолчанию | Где читается |
|---|---|---|---|
| `workerEnabled` (`worker.enabled`) | Главный выключатель фона | `true` (`ADC:1386`) | `SCMS:271` (кеш), `CMAP:15`, `CMAP:23` (свежо), `CIOE:80`, `SCMS:408` |
| — (`paused-until`, `pause-reason`) | Пауза до момента. В форме поля нет; записывает только защита от массовых ошибок (`SCMS:4812-4816`) | пусто | `SCMS:276-284`, `CMAP:16-17`, `CMAP:24-25`, `CIOE:90-99`; показ `FE/features/admin/dictionaries/admin-dictionaries.component.html:234-236` |
| `liveEnabled` (`live.enabled`) | Реальная отправка, иначе dry-run | `true` (`ADC:1387`) | `SCMS:2555-2562`, `CIDS:402`, `CIDS:475-478` |
| `immediateEnabled` (`immediate.enabled`) | Моментальные сообщения по событию: первый счёт, счёт после плохой задачи, «оплата прошла» | `true` (`ADC:1388`) | `OSTS:1151`, `SCMS:2032`, `BE/common_billing/service/CommonInvoiceSettlementService.java:2226-2228`, `CIDS:263-267`, `BE/bad_reviews/service/BadReviewTaskServiceImpl.java:340` |
| `paymentReminderEnabled` (`payment-reminder.enabled`) | Напоминания об оплате (заказ, общий, бумажный счёт) | `true` (`ADC:1393`) | `CMAP:31`, `SCMS:1608`, `SCMS:1702`, `CommonInvoiceSettlementService.java:2230-2232`, `CIOE:81` |
| `paymentReminderIntervalDays` | Интервал напоминаний, дни (1–365) | `2` (`SCMS:117`) | `SCMS:4479-4486`, `CommonInvoiceSettlementService.java:2234-2245` |
| `paymentReminderStatuses` | Статусы заказа для напоминаний | `Выставлен счет,Напоминание` (`SCMS:98`) | `SCMS:1448`, `SCMS:1606`, `SCMS:1706`, `SCMS:3008` |
| `paymentOverdueEnabled` (`payment-overdue.enabled`) | Вести задачу просрочки | `true` (`ADC:1397`) | `CMAP:34`, `SCMS:1611`, `SCMS:1712`, `CIOE:82` |
| `paymentOverdueLiveEnabled` (`payment-overdue.live-enabled`) | Реально переводить в «Не оплачено» | `false` (`ADC:1398`) | `SCMS:3487`, `SCMS:259`, `CIOE:83` |
| `paymentOverdueDays` (`payment-overdue-days`) | Срок оплаты, дни (1–365) | `30` (`SCMS:128`) | `SCMS:1546-1548`, `SCMS:1713`, `CIOE:101-109` (минимум 3), `ClientMessageMaintenancePreviewService.java:122-125` (запасное значение 60) |
| `paymentOverdueStatuses` | Статусы, из которых идёт просрочка | `Выставлен счет,Напоминание` (`SCMS:99`) | `SCMS:1716`, `SCMS:3465` |
| `paymentOverdueTargetStatus` (`payment-overdue.target-status`) | Целевой статус автоперевода одиночного заказа | `Не оплачено` (`SCMS:101`) | только `SCMS:3483-3486`; общий счёт и актуализация пишут «Не оплачено» жёстко (`CIAW:215`, `ClientMessageMaintenancePreviewService.java:142`) |
| `closedOrderStatuses` | Статусы, при которых платёжные задачи закрываются | `Оплачено,Архив,Бан,Не оплачено` (`SCMS:100`) | `SCMS:1820`, `SCMS:3461`, `SCMS:4632-4640` |
| `paymentInvoiceRetryDelayHours` | Повтор первого счёта после сбоя, часы | `2` (`SCMS:122`) | `SCMS:4506-4513`, `PIRS:57-60` |
| `badReviewInvoiceEnabled` | Счёт с новой суммой после плохой задачи | `true` (`ADC:1394`) | `CMAP:35`, `SCMS:2033`, `SCMS:2139-2140`, `CIDS:466-468`, `CIMem:350` |
| `badReviewInvoiceRetryDelayHours` | Повтор такого счёта, часы | `2` (`SCMS:123`) | `SCMS:4515-4522`, `PIRS:109-112` |
| `badReviewAutoBanEnabled` | Автобан после финального счёта за плохие | `true` (`ADC:1395`) | `CMAP:36`, `SCMS:2943-2953`, `SCMS:3344` |
| `badReviewAutoBanDelayDays` | Ожидание перед автобаном, дни | `2` (`SCMS:125`) | `PIRS:519-524`, `SCMS:4533-4540` |
| `businessWindows` (`business-windows`) | Рабочие окна, время Иркутска | `10:00-12:00,14:00-17:00,19:00-21:00` (`ClientMessageSlotPlanner.java:15-16`) | `SCMS:4593-4601`, `SCMS:326-330`, `PIRS:532-540` |
| `dailyLimit` | Общий дневной лимит фоновых сообщений | `140` (`SCMS:134`) | `SCMS:4195-4204` |
| `defaultGapSeconds` / `whatsAppGapSeconds` / `telegramGapSeconds` / `maxGapSeconds` | Интервал между отправками по каналу (30–86400 с) | `180` / `180` / `90` / `90` (`SCMS:135-138`) | `SCMS:4399-4411` |
| `tickBatchSize`, `candidateLimit` | Задач за тик; кандидатов за сверку | `5`, `200` (`SCMS:133`, `SCMS:130`) | `SCMS:332`, `SCMS:4578-4580` |
| `transientRetryMinutes` | Повтор временной ошибки | `15` (`SCMS:141`) | `SCMS:4185-4193` |
| `errorProtectionEnabled` / `...Threshold` / `...WindowMinutes` / `...CooldownMinutes` | Автопауза при массовых ошибках | `true` / `20` / `10` / `60` (`ADC:1400`, `SCMS:144-146`) | `SCMS:4777-4818` |
| `reviewCheckAutoArchiveEnabled` | «Архив после 7 напоминаний» (оба сценария молчания) | `true` (`ADC:1391`) | `SCMS:774`, `SCMS:881`, `SCMS:4233` |
| `archiveReorderEnabled`, `archiveReorderMonths`, `archiveReorderJitterDays` | Предложение нового заказа компании «На стопе» | `true`, `3`, `10` (`ADC:1399`, `SCMS:126-127`) | `CMAP:30`, `SCMS:904`, `SCMS:4551-4567` |
| `paymentInstructionSource` (`payment-instruction-source`) | Источник реквизитов в счёте/напоминании | `MANAGER_TEXT` (`SCMS:110`) | `SCMS:4619-4625`; запись — `BE/payments/service/TbankRuntimeSettingsService.java:76`, а не `PUT /settings/client-messages` |
| `overdueExtensionDays` (поле общего счёта, не настройка) | Отсрочка менеджера для одного общего счёта, 0–60 дней | `0` | `CommonInvoiceOverdueTerm.java:23`, `:52-72`; `CIOE:264-266` |

Параметры сервера (не на экране): `client.messages.tick-delay-ms` = 30000 (`SCMS:263-266`); `client.messages.reconcile-interval` = PT5M (`SCMS:218-219`); `common-billing.reminders.fixed-delay` = PT5M (`CommonBillingReminderScheduler.java:16`); `common-billing.message-queue-delay-ms` = 5000 (`CIMW:23`); `common-billing.overdue-escalation.cron` = `0 7/15 10-19 * * *`, Иркутск (`CIOE:185-188`); `manager.common-invoice-overdue-notice.cron` = `0 */15 10-19 * * *` и ключ `manager.common-invoice-overdue-notice.enabled` = `true` (`ThematicStaffNotificationJob.java:67`, `:74-81`); `payment.attention.interval-ms` = 300000 (`PaymentAttentionReminderJob.java:44-45`).

Экран: права `ADMIN`/`OWNER` (`ADC:823-831`); форма и значения по умолчанию в интерфейсе — `FE/features/admin/dictionaries/admin-client-message-settings.facade.ts:40-55`, `:85`, `:106`, `:127-139`, `:142-154`; сохраняется весь набор одним запросом (`facade.ts:513-670`). Включение `liveEnabled` сразу возвращает dry-run задачи в очередь (`ADC:843-845`).

## Путь неоплаченного счёта по времени — по шагам

### А. Одиночный заказ

1. **«Опубликовано».** `handlePublicStatus` ставит задачу `PAYMENT_INVOICE_RETRY` на «сейчас» (`OSTS:1167`, `PIRS:65-75`); то же при автоматическом завершении публикации (`BE/p_products/service/OrderStatusCheckerServiceImpl.java:153-154`). Не ставит, если заказ ушёл в общий счёт (`OSTS:1145`) или `immediateEnabled=false` (`OSTS:1151`). Страховка — сверка раз в 5 минут создаёт задачу со сроком «смена статуса + `paymentInvoiceRetryDelayHours`» (`SCMS:1700`, `SCMS:3221-3248`).
2. **Отправка счёта.** `retryPaymentInvoice` готовит платёжное сообщение и действие «→ Выставлен счет» (`SCMS:3160-3207`). Статус меняется только после подтверждённой доставки (`SCMS:2693-2695`). Сбой подготовки платёжной инструкции — запись `payment_instruction_failed`, повтор через день и карточка «Платёж требует внимания» менеджеру (`SCMS:2491-2497`, `SCMS:3861-3863`, `SCMS:3803-3824`).
3. **«Выставлен счет».** Слушатель после коммита (`ClientMessageOrderStatusChangedListener.java:18-25`) закрывает задачи старого цикла и создаёт `PAYMENT_REMINDER` (смена статуса + `paymentReminderIntervalDays`) и `PAYMENT_OVERDUE_ESCALATION` (смена статуса + `paymentOverdueDays`) (`SCMS:1559-1587`, `SCMS:1538-1551`, `SCMS:1606-1613`). Ключ цикла — `order:{id}:{statusChangedAt}` (`SCMS:4700-4702`).
4. **Первое напоминание.** `sendOrderReminder` (`SCMS:3064-3124`). После подтверждения, если заказ всё ещё «Выставлен счет» и поколение сообщений не менялось, заказ переводится в «Напоминание», задача закрывается (`SCMS:2696-2699`, `SCMS:3636-3645`).
5. **«Напоминание».** Новый `statusChangedAt` (`BE/p_products/model/Order.java:132-137`) → новый цикл: напоминания каждые `paymentReminderIntervalDays` (`SCMS:3699-3716`, `SCMS:4174`). Ограничения по количеству для платёжных напоминаний нет (предел в 7 есть только у проверки отзывов, `SCMS:3103`). Срок просрочки отсчитывается заново от входа в «Напоминание» (`SCMS:1546-1548`).
6. **За 2 суток до срока** менеджеру создаётся личная карточка (не клиенту): `PaymentAttentionReminderService.java:25-43`; только если включены просрочка, её live-режим и напоминания (`SCMS:257-261`).
7. **Срок вышел.** `escalateOverduePayment` (`SCMS:3450-3519`): live выключен → запись `payment_overdue_dry_run`, повтор через день (`SCMS:3487-3505`); live включён → `changeStatusForOrder(id, paymentOverdueTargetStatus)` (`SCMS:3507-3518`).
8. **«Не оплачено».** Создаются плохие задачи по опубликованным отзывам с шагом 2 дня, освобождается резерв подрядчика (`OSTS:487-496`, `BadReviewTaskServiceImpl.java:80`, `:111-154`). Платёжные задачи закрываются: сценариев для этого статуса нет (`SCMS:1589-1626`), сверка добивает оставшиеся (`SCMS:1816-1835`).
9. **Выполнена плохая задача.** В одной транзакции с `DONE` ставится `BAD_REVIEW_INVOICE` (`BadReviewTaskServiceImpl.java:253-267`, `PIRS:100-114`); после коммита — моментальная отправка счёта с новой суммой (`BE/bad_reviews/service/BadReviewCompletionPostActionOrchestrator.java:25-38`, `SCMS:2029-2056`). Если открыта прежняя банковская ссылка — отправка откладывается до её закрытия (`BadReviewTaskServiceImpl.java:253-254`, `BE/payments/service/OrderBadReviewBankRepricing.java:50`, `PIRS:93-98`).
10. **Автобан.** После доставки счёта, когда ожидающих задач нет и есть выполненные, ставится `BAD_REVIEW_AUTO_BAN` на `badReviewAutoBanDelayDays` (`SCMS:2120-2122`, `SCMS:3214-3219`, `PIRS:130-142`); исполнение — `autoBanAfterBadReviews` → «Бан» (`SCMS:3343-3425`).
11. **Оплата на любом шаге.** Платёжные задачи закрываются вместе с платежом (`PIRS:206-221`), автобан отменяется (`OSTS:532`), ожидающие плохие задачи отменяются и открывается заявка на следующий заказ (`BE/p_products/service/OrderTransactionServiceImpl.java:146-154`); сообщение об успешной оплате зависит от `immediateEnabled` (`BE/payments/service/PaymentSuccessClientNotifier.java:40`, `:78`).

### Б. Общий счёт

1. **Все заказы готовы → `READY`.** При `immediateEnabled` счёт отправляется после коммита, иначе `auto_send_disabled` (`CIMem:248-257`). Страховка — `sendUnsentActionInvoices` раз в 5 минут (`CommonBillingReminderScheduler.java:24-30`, `CIDS:260-278`).
2. **Отправка.** Сообщение кладётся в `common_invoice_message_queue` (`CommonInvoiceMessageQueue.java:55-63`), воркер каждые 5 с (`CIMW:23-63`). После подтверждения: `INVOICED`, `sentAt`, заказы → «Выставлен счет», `nextReminderAt = сейчас + интервал` (`CIDS:560-565`).
3. **Напоминания.** `sendDueReminders` (`CIDS:248-258`) → очередь → допуск бюджета (`CIMW:47-55`) → `REMINDER`, заказы → «Напоминание», новый `nextReminderAt` (`CIDS:556-565`). Количество не ограничено. Доказанно неотправленное сообщение остаётся в очереди тем же конвертом: до 5 реальных попыток с нарастающей задержкой, затем `FAILED` и `lastError = delivery_failed…` (`CIDS:528-533`, `CIDS:538`).
4. **Срок.** От `sentAt` (напоминания его не сдвигают, `CIDS:556-559`) плюс `paymentOverdueDays` плюс отсрочка (`CIOE:264-266`). За `NOTICE_DAYS = 2` дня менеджеру уходит уведомление в группу, и только доставленное уведомление записывается (`ThematicStaffNotificationJob.java:91-121`, `CIOE:139-154`).
5. **Автоперевод.** `escalate()` (`CIOE:185-226`) → `markUnpaidAfterOverdue` (`CIAW:116-126`) → заказы «Не оплачено», плохие задачи, статус `UNPAID`, `nextReminderAt = null` (`CIAW:197-237`).
6. **`UNPAID`.** После каждой выполненной плохой задачи сумма пересчитывается и клиенту уходит счёт с новой суммой (`CIMem:300-352`); при банковской ссылке сначала ждут закрытия прежней (`CommonInvoiceBadReviewBankRepricing.java:29-47`, `:93-130`). Пока цикл идёт сам, менеджер не нужен; причина для менеджера появляется при остановке цикла (`CIAW:133-179`).
7. **«Бан» общего счёта — только вручную** (`CIAW:386-433`); автоматического вызова `markBan` в коде нет (единственный внешний вызов — `CBAC:325`).

### В. Бумажный счёт

- Одиночный заказ: при удержании задачи не создаются (`SCMS:1451`, `SCMS:1462`), активные закрываются `paper_invoice_cancelled` (`SCMS:3292-3294`, `SCMS:3332-3335`). Доставка документов переводит заказ в «Выставлен счет» и начинает новый цикл напоминаний, даже если статус не изменился (`OSTS:189-205`, `PaperInvoiceOrderAdapter.java:115-119`). Пока документ не выпущен, напоминание не уходит: `payment_instruction_failed` (`SCMS:4025-4028`, `SCMS:3115-3120`).
- Общий счёт: напоминания начинаются только после отметки «счёт выдан» (`CIDS:565`, `CIDS:657-659`); срок оплаты — от доставки всех комплектов (`CommonInvoiceOverdueClock.java:26-59`).
- Комплекты общего счёта: `PaperBillingReminders.tick()` каждые 30 с, не более 7 напоминаний на комплект, затем «Требуется проверка оплаты менеджером» (`PaperBillingReminders.java:22-30`, `:64-70`); интервал — тот же `paymentReminderIntervalDays` (`BE/common_billing/service/BankPaperInvoicePartsService.java:40-46`).

### Г. Автоархив молчания и предложение нового заказа (до счёта)

Автоархив относится к заказам «Новый» (ждёт клиента) и «На проверке», а не к выставленному счёту: 7 подтверждённых напоминаний + интервал, затем проверка переписки (`SCMS:832-844`, `SCMS:768-794`). Оплата, публикации или подтверждённые поступления блокируют архив (`SCMS:886-891`). Запуск — `CommonInvoiceReminderApprovalScheduler.java:17-37` (PT5M). После архива ставится предложение нового заказа, если компания «На стопе» и нет активных заказов и заявок (`SCMS:904-917`, `SCMS:942-961`).

## Как применяются выключатель, пауза, рабочие окна, лимит и интервалы

| Автоматический шаг | Задание | Свой переключатель | Worker и пауза | Рабочие окна | Дневной лимит | Интервал канала / аккаунта |
|---|---|---|---|---|---|---|
| Первый счёт заказа (`PAYMENT_INVOICE_RETRY`) | `SCMS.tick` | нет (`CMAP:33`); live | да (`SCMS:271-284`, `SCMS:2939`, `SCMS:2521`) | да (`SCMS:326-330`) | да (`SCMS:2966`) | да (`SCMS:2977-2990`, `SCMS:3603-3634`) |
| Напоминание заказа (`PAYMENT_REMINDER`) | `SCMS.tick` | `paymentReminderEnabled` | да | да | да | да |
| Перевод заказа в «Не оплачено» (`PAYMENT_OVERDUE_ESCALATION`) | `SCMS.tick` | `paymentOverdueEnabled` + live-флаг | да | да | да — ждёт лимит и интервал, хотя сообщение не шлёт (`SCMS:4614-4617`) | канал — да (`SCMS:2977-2990`); аккаунт — нет |
| Счёт после плохой задачи, моментально (есть `taskId`) | после коммита, `SCMS:2029-2056` | `immediateEnabled` + `badReviewInvoiceEnabled` + live (`SCMS:2032-2034`) | **нет** (`SCMS:2073`) | **нет** | **нет** (`SCMS:2197`) | **нет** (`SCMS:2200`) |
| Счёт после плохой задачи, фоновый повтор | `SCMS.tick` | `badReviewInvoiceEnabled` | да (`SCMS:2073-2075`) | да | да (`SCMS:2396-2406`) | да (`SCMS:2407-2418`, `SCMS:2200`) |
| Автобан (`BAD_REVIEW_AUTO_BAN`) | `SCMS.tick` | `badReviewAutoBanEnabled` | да | да (тик) | нет (`SCMS:4614-4617`) | нет |
| Первый счёт общего счёта, смена маршрута, ручные отправки | `CIMW.tick` | `immediateEnabled` при постановке; live (`CIMW:40-42`) | **нет** | **нет** | **нет** | **нет** (`CIMW:69`) |
| Напоминание общего счёта | `CommonBillingReminderScheduler` → `CIMW` | `paymentReminderEnabled` (`CIDS:249`, `CIDS:355`) | да (`SCMS:4327-4333`) | да (`SCMS:4349-4353`) | да (`SCMS:4354-4357`) | да (`SCMS:4358-4365`) |
| Счёт с новой суммой общего счёта | `CIMW.tick` | `badReviewInvoiceEnabled` + `immediateEnabled` (`CIDS:366-367`) | да (`SCMS:4325-4333`) | да | да | да |
| Перевод общего счёта в «Не оплачено» | `CIOE.escalate` | worker + напоминания + просрочка + live (`CIOE:74-87`) | да (`CIOE:90-99`) | свой cron 10–19 ч Иркутска; настройку окон не читает (`CIOE:185-188`) | — | — |
| Напоминание бумажного комплекта | `PaperBillingReminders` | как напоминание (`PaperBillingReminders.java:52-63`) | да | да | да | да |
| Автоархив молчания | `SCMS:768-794` | `reviewCheckAutoArchiveEnabled` + переключатель напоминаний | да (`SCMS:772`, `SCMS:864`, `SCMS:880-881`) | — | — | уведомления — общий бюджет (`SCMS:4207-4226`) |

Правила применения:

- **Выключатель и пауза.** `tick()` выходит до любой работы (`SCMS:271-284`). Уже взятая задача откладывается на день с кодом `automation_paused` (`SCMS:2939-2942`, `SCMS:2356-2371`); когда разрешение вернулось — сразу возвращается в очередь (`SCMS:2435-2441`). Сроки и счётчики не сбрасываются.
- **Пауза между подготовкой и отправкой.** Провайдер не вызывается, конверт и токен сохраняются, статус доставки `RETRYABLE` (`SCMS:2519-2526`, `SCMS:2565-2581`); возврат в очередь — `releaseDryRunStates` на следующем разрешённом тике (`SCMS:309`, `SCMS:3677-3686`).
- **Рабочие окна.** Вне окна тик не берёт задачи (`SCMS:326-330`); любой назначаемый срок сдвигается в ближайшее окно (`SCMS:4450-4454`); интервал считается внутри окон (`ClientMessageSlotPlanner.java:59-71`).
- **Дневной лимит.** Считаются отправленные попытки (кроме канала `system`), резервы уведомлений об архиве, подготовленные доставки и внешние резервы (`SCMS:4195-4204`, `BE/client_messages/repository/ScheduledClientMessageAttemptRepository.java:31-38`). Исчерпан → перенос на 10:00 следующего дня в окне (`SCMS:2966-2969`, `SCMS:4456-4459`).
- **Интервал.** Последняя отправка канала — максимум из сохранённого времени, резервов очереди, уведомлений и внешних резервов (`SCMS:4237-4249`); отказ — `rate_limited` (`SCMS:2986-2989`).
- **Общий счёт при отказе бюджета.** Сообщение остаётся в очереди, попытка не тратится, повтор через 300 с (`CIMW:47-52`, `CIDS:526-527`, `CIDS:538-539`).
- **Защита от массовых ошибок** сама ставит паузу на `errorProtectionCooldownMinutes` (`SCMS:4777-4818`).

## Защита от дублей и повторная проверка разрешения

Защита от дублей:

- **Уникальная задача на цикл.** Пара `(scenario, targetKey)` уникальна (`BE/client_messages/model/ScheduledClientMessageState.java:25-31`); закрытая задача с отправками не переоткрывается сверкой (`SCMS:1947-1963`).
- **Захват.** `lockDueState` атомарно берёт задачу на 5 минут и помечает `CLAIMED` (`BE/client_messages/repository/ScheduledClientMessageStateRepository.java:641-661`, `SCMS:341-347`); чужой или истёкший захват не обрабатывается (`SCMS:2845-2858`).
- **Подготовка → коммит → отправка → финализация.** Конверт (получатель, текст, реквизиты, operationId) сохраняется до вызова провайдера (`SCMS:2583-2613`); вызов идёт без транзакции (`SCMS:2549-2553`); финализация сверяет токен, сценарий, ключ и заказ (`SCMS:2633-2638`). Описание — `docs/ADR-009-SCHEDULED-DELIVERY-TRANSACTIONS.md:7-11`.
- **Неизвестный исход.** Статус `UNKNOWN`, срок следующей попытки очищается, повтор запрещён (`SCMS:2672-2683`); восстановление ищет только квитанцию прежней операции (`ScheduledDeliveryRecovery.java:36-68`, `docs/ADR-004-DELIVERY-AND-RECOVERY.md:12-14`).
- **Доказанная неотправка.** Сохраняется тот же конверт (`RETRYABLE`), новый текст не строится (`SCMS:2684-2690`).
- **Telegram/MAX.** Локальный барьер по operationId и хешу конверта (`ClientMessageOperationFence.java:27-46`, `docs/CLIENT_MESSAGE_OPERATION_FENCE.md:3-7`).
- **Общий счёт.** Одна неразрешённая операция на счёт (`CIDS:212-223`); operationId хранится в счёте (`CIDS:435-441`); вставка в очередь идемпотентна (`CommonInvoiceMessageQueue.java:62-63`); не более 5 реальных попыток (`CIDS:528-533`).
- **Уже оплачено.** Платёжное сообщение подавляется при подтверждённой оплате (`SCMS:3295-3306`, `SCMS:2173-2176`).
- **Ручной контакт.** Сохранённая ручная отправка текущего цикла откладывает автоматическую на 5 минут (`SCMS:2861-2864`).
- **Заявка на следующий заказ** — одна на исходный заказ (`BE/p_products/next_order/service/NextOrderRequestService.java:79-91`).

Повторная проверка разрешения:

| Действие | Перед изменением заказа / счёта | Перед внешней отправкой |
|---|---|---|
| Напоминание и первый счёт заказа | `SCMS:2939` (политика), `SCMS:2954-2962` (проверка оплаты, удержание), статус и цикл под блокировкой заказа (`SCMS:3073-3102`) | `SCMS:2521` (live + политика) |
| Перевод заказа в «Не оплачено» | `SCMS:2939` → блокировка заказа `SCMS:3451` → статус, цикл, общий счёт `SCMS:3460-3481` → переход `SCMS:3508`, всё в одной транзакции подготовки | — |
| Автобан | `SCMS:2939`, `SCMS:2943-2953`, `SCMS:3344`, оплата и статус `SCMS:3362-3403` → `SCMS:3412` | — |
| Счёт после плохой задачи | `SCMS:2139-2202` под блокировками заказа и задачи | `SCMS:2073-2080` |
| Сообщение общего счёта | подготовка под блокировками заказов, ссылок и счёта (`CIDS:305-311`, `CIDS:353-453`) | `CIMW:40-55`: live, `queuedMessageMayDispatch` (`CIDS:480-488`), допуск бюджета |
| Перевод общего счёта в «Не оплачено» | `CIOE:192`, `CIOE:230`, затем `stillDue` под блокировкой счёта (`CIOE:238-245`, `CIAW:118-123`) | — |
| Автоархив молчания | `SCMS:772-775` до чтения истории, `SCMS:880-891` под блокировками | уведомления: `BE/client_messages/service/ReviewArchiveNotifications.java:135`, `:171` |
| Напоминание бумажного комплекта | повторное чтение документа и `remindersAllowed` (`PaperBillingReminders.java:36-51`) | `PaperBillingReminders.java:52-63` |

## Особые состояния (смена маршрута, бумажный счёт, проверка ручной оплаты, «требует внимания»)

**Смена маршрута.**
- Общий счёт: статус `READY` (или `PARTIALLY_PAID`), `sentAt`, `lastReminderAt`, `nextReminderAt` обнуляются, в `lastError` пишется `payment_route_changed_message_pending` (`BE/common_billing/service/CommonInvoicePaymentRouteWorkflow.java:326-330`, `:367-370`). Сообщение уходит с предупреждением «Способ оплаты изменён…» (`CIDS:860-862`). Если немедленная отправка не состоялась, досылает планировщик — даже при `immediateEnabled=false` (`CIDS:263-265`, `CommonInvoiceRepository.java:328-340`). При постановке в очередь маркер сменяется на `payment_route_changed_message_in_progress` (`CIDS:450`), после подтверждённой доставки `lastError` очищается (`CIDS:566`); неотправленное сообщение остаётся в очереди и повторяется тем же конвертом (`CIDS:526-539`). Значение `payment_route_changed_message_retry:` по-прежнему распознаётся как ожидающая досылка (`CIDS:606-609`). Срок оплаты начинается заново с новой отправки: прежнее уведомление менеджера к новому `sentAt` не относится (`CIOE:268-271`). Пока маркер стоит, автоперевода нет — нужен пустой `lastError` (`CIOE:260`).
- Одиночный заказ: смена маршрута запрещена, пока есть сохранённое неотправленное или неподтверждённое сообщение со старыми реквизитами (`PIRS:305-334`, вызов `BE/payments/service/PaymentRouteReplacementWorkflow.java:193`). Новые реквизиты отправляет отдельный outbox каждые 15 с (`BE/payments/service/PaymentRouteChangeNotificationRetryService.java:21-25`, `BE/payments/service/PaymentRouteChangeNotificationWorker.java:50-120`).

**Бумажный счёт (`paperInvoiceAutomationHold`).**
- Удержание ставят: проверка поступления по отменённому счёту (`BankPaperInvoiceAdapter.java:81-86`, `PaperInvoiceOrderAdapter.java:43-47`), запрос удержания (`BankPaperInvoiceAdapter.java:88-94`), отмена документов (`BankPaperInvoiceAdapter.java:96-104`, `PaperInvoiceOrderAdapter.java:61-70`). Снимают: привязка нового документа (`BankPaperInvoiceAdapter.java:65-69`) и возврат к обычному счёту (`BankPaperInvoiceAdapter.java:111-122`, `PaperInvoiceOrderAdapter.java:77-89`).
- Под удержанием: общий счёт не отправляет и не напоминает (`CIDS:210`, `CIDS:602-604`, `CIDS:484`); одиночные платёжные задачи не создаются и закрываются (`SCMS:1619-1624`, `SCMS:3292-3294`).
- Автоперевод общего бумажного счёта: удержание на уровне счёта его не блокирует (`CIOE:253-255`); блокируют `paperInvoiceReviewHold` (`CIOE:256`) и незавершённые комплекты — недоставленный, оплаченный или с удержанием `automation_hold` комплект не даёт начаться сроку (`CommonInvoiceOverdueClock.java:30-45`).
- Возврат к обычному счёту ставит маркер смены маршрута и отправку (`BankPaperInvoiceAdapter.java:119-121`, `CIDS:191-199`); для заказа — `scheduleOrdinaryInvoice`, только при банковском источнике реквизитов (`SCMS:1376-1386`).

**Проверка ручной оплаты.**
- Заказ: пока есть неразрешённая проверка перевода специалисту (`BE/payments/service/SpecialistManualPaymentReviewGuard.java:24-30`, `:78-82`), платёжные сценарии не назначаются (`SCMS:1447-1449`), активные задачи закрываются `manual_payment_review` (`SCMS:2954-2959`, `SCMS:2168-2171`), новые не ставятся (`PIRS:409`).
- Общий счёт: сообщение клиента об оплате (`clientReportedAt`) и частичная оплата исключают автоперевод (`CIOE:258-259`); банковская сверка ручного платежа переводит счёт в `NEEDS_ATTENTION` и снимает `nextReminderAt` (`BE/common_billing/service/CommonInvoiceManualPaymentWorkflow.java:494-496`, `:646-648`).

**«Требует внимания».**
- Общий счёт `NEEDS_ATTENTION`: отправки, напоминания и перевод в «Не оплачено» отклоняются (`CIDS:611-616`, `CIAW:198`); статус не входит в списки напоминаний и автоперевода (`CIDS:68`, `CIOE:43-44`); в интерфейсе действий нет (`FE/features/manager/manager-board.config.ts:574-577`).
- Одиночный заказ: сбой платёжного сценария создаёт менеджеру карточку «Платёж требует внимания» (`SCMS:3803-3824`); ошибки, требующие ручного исправления, отключают задачу после 30 неудач (`SCMS:3857-3860`, `SCMS:143`).

**История чата недоступна.**
- Перевод в «Не оплачено» историю чата не читает (`SCMS:3450-3519`, `CIOE:238-245`).
- Автоархив молчания откладывается на 5 минут и ничего не меняет (`SCMS:783-789`).
- Сообщение с неизвестным исходом остаётся `UNKNOWN`: отсутствие квитанции не считается неотправкой (`ScheduledDeliveryRecovery.java:60-68`; общий счёт — `CIDS:154-160`, `CIDS:534-536`).

**Восстановление отзывов.** Задачи заказа откладываются на 10 минут (`SCMS:3737-3756`); напоминание общего счёта — на день с `review_recovery_active` (`CIDS:386-392`, `CIDS:820-827`).

## Ручные действия и права

| Автоматический шаг | Ручной аналог | Сервер: кто может | Интерфейс: кто видит |
|---|---|---|---|
| Перевод заказа в «Не оплачено» | `POST /api/manager/orders/{id}/status` | `ADMIN`, `OWNER`, `MANAGER`, `WORKER` (`BE/manager/controller/ApiManagerOrderController.java:60-73`); только-специалисту разрешён лишь «В проверку» (`OrderStatusCommandService.java:46-56`) | с 2026-11-10 только `OWNER`/`ADMIN`: сайт `FE/features/manager/manager-board.config.ts:564-573`, `:650-652`, вызов `FE/features/manager/manager-board.component.ts:1300-1304`; доска специалиста `FE/features/worker/worker-board.component.ts:156-162`; приложение `MOB/features/manager/manager-board.helpers.ts:88-97`, `:156-158`, вызов `MOB/features/manager.page.ts:3563` |
| То же, старые формы | `POST /orders/status_no_pay/...`, `/status_no_pay2/...` | `ADMIN`, `OWNER`, `MANAGER` (`BE/p_products/controller/OrderController.java:311-321`, `:422-430`; `OrderStatusCommandService.java:37`) | — |
| Перевод общего счёта в «Не оплачено» | `POST /api/common-billing/invoices/{id}/unpaid` | `ADMIN`, `OWNER`, `MANAGER` + право управлять счётом (`CBAC:314-319`, `CBAC:401-403`) | только `OWNER`/`ADMIN`: `FE/features/admin/common-billing/common-billing.component.ts:682-694`, `:1373-1383`; `MOB/features/common-billing.page.ts:1477-1487`; на карточке заказа общего счёта `manager-board.config.ts:588-592`, `manager-board.helpers.ts:107-111` |
| Отправка и напоминание общего счёта | `POST .../send`, `POST .../remind` | `ADMIN`, `OWNER`, `MANAGER` (`CBAC:110-118`, `CBAC:199-207`); ручные сообщения бюджет не проверяют (`CIMW:69`) | — |
| Отсрочка срока общего счёта | `PUT .../overdue-term` (0–60 дней) | `ADMIN`, `OWNER`, `MANAGER` (`CBAC:390-399`, `CommonInvoiceOverdueTerm.java:52-72`) | `FE/features/admin/common-billing/common-invoice-overdue-term.component.ts:57-69`, `:137-145` |
| «Бан» заказа | тот же `.../status` | из «Не оплачено» или после финального счёта за плохие; с ожидающими плохими задачами — только `ADMIN`/`OWNER` (`OSTS:537-567`, `OrderStatusCommandService.java:61-62`) | — |
| «Бан» общего счёта | `POST .../ban` | `ADMIN`, `OWNER`, `MANAGER`; менеджеру — после выполнения всех плохих задач (`CBAC:321-326`, `CIAW:401-407`) | — |
| Повтор, отключение, закрытие задачи автоответчика | `POST /api/admin/client-messages/monitor/{id}/retry-now`, `/disable`, `/done` | `ADMIN`, `OWNER` (`ClientMessageMonitorController.java:83-99`); задачи с неизвестным исходом отклоняются (`ClientMessageMonitorService.java:269-312`) | экран справочников |
| Немедленная обработка задачи из «Контроля» | `ScheduledClientMessageService.retryNow` | отказ при выключенном worker или паузе (`SCMS:408-424`); вызывается из `BE/manager_control/service/ManagerControlAutomationRepairWorkflow.java:115` | — |
| Актуализация «просроченные счета» | `POST /api/admin/client-messages/maintenance/payment-overdue` | только `ADMIN` (`ClientMessageMonitorController.java:44-48`) | кнопка с подтверждением: `admin-dictionaries.component.html:423`, `FE/features/admin/dictionaries/admin-message-monitor.facade.ts:254-284` |

**Факт о сервере.** Роль на переход «Не оплачено» не проверяется ни в `OrderStatusTransitionService` (`OSTS:263-335`, `OSTS:487-496`), ни в `CommonInvoiceArchiveWorkflow.markUnpaid` (`CIAW:102-109`): менеджер по-прежнему может выполнить переход прямым запросом к API. Файлы `OrderStatusCommandService`, `OrderStatusTransitionService` и `CommonInvoiceArchiveWorkflow` в рабочем дереве не изменены; в `CommonBillingAdminController` (его правит параллельная задача) аннотация маршрута `/unpaid` на момент сверки — `hasAnyRole('ADMIN', 'OWNER', 'MANAGER')` (`CBAC:314`). Изменение от 2026-11-10 касается только интерфейсов. Допустимые исходные статусы для «Не оплачено» — `OSTS:91`; для общего счёта — `INVOICED`, `REMINDER`, `PARTIALLY_PAID` (`CIAW:76`).

**Админ-актуализация `payment-overdue`** (`ClientMessageMaintenancePreviewService.java:121-156`):
- берёт до 200 заказов в «Выставлен счет»/«Напоминание» (`:32`, `:126-136`) старше `paymentOverdueDays`; запасное значение здесь 60, а не 30 (`:122-125`);
- вызывает `changeStatusForOrder(id, "Не оплачено")` по одному, статус жёстко задан (`:139-147`);
- не читает выключатель, паузу, `paymentOverdueLiveEnabled`, окна и лимит; не проверяет удержание бумажного счёта, проверку ручной оплаты и восстановление отзывов — это ручное действие администратора;
- заказы активного общего счёта отклоняются правилом перехода и попадают в счётчик «не удалось» (`OSTS:415-420`, `:148-155`).

## Сценарии — таблица: условие → что делает система → где в коде → каким тестом покрыто (или «нет теста»)

| Условие | Что делает система | Где в коде | Тест |
|---|---|---|---|
| Worker выключен, пауза, выключен сценарий | Разрешения нет; после включения — снова есть | `CMAP:21-41` | `ClientMessageAutomationPolicyTest.everyBackgroundScenarioHonorsStopPauseAndResume` |
| Настройки не читаются | Запрет (fail closed) | `CMAP:40`, `SCMS:2555-2562` | `ScheduledDeliveryProtocolTest.unavailableFreshSwitchReadProvesNoDispatchAndKeepsRetryableSnapshot` |
| `liveEnabled=false` | Попытка `SKIPPED`, отправки нет, повтор через день | `SCMS:3647-3670` | `ScheduledClientMessageServiceTest.liveDisabledRecordsDryRunSkipWithoutFailureOrSenderCall` |
| Live включили обратно | Dry-run задачи возвращаются в очередь | `SCMS:3677-3686` | `ScheduledClientMessageServiceTest.liveEnabledReleasesDryRunStatesBackToQueue` |
| Отправку выключили после подготовки | Провайдер не вызван, конверт сохранён и уходит тем же после включения | `SCMS:2519-2526`, `SCMS:2565-2581` | `ScheduledDeliveryProtocolTest.liveOffBetweenPreparationAndDispatchPausesAndResumesTheSameFrozenMessage`; `ScheduledDeliveryTransactionMySqlIntegrationTest.anotherInstanceCanDisableLiveAfterCommitDespiteWarmOnCacheAndResumeTheSameEnvelope` |
| Вне рабочего окна (заказ) | Тик не берёт задачи | `SCMS:326-330` | прямого теста тика нет; расчёт окон — `ClientMessageSlotPlannerTest.movesAfterNineToNextMorning` |
| Вне рабочего окна (общий счёт) | Напоминание ждёт в очереди, попытка не тратится | `SCMS:4349-4353`, `CIDS:538-539` | `CommonBillingServiceTest.automaticReminderHeldForTheBusinessWindowGivesItsAttemptBack`; `CommonInvoiceMessageQueueMySqlIntegrationTest.aNightWaitingForTheBusinessWindowKeepsEveryRetryForTheRealSend` |
| Дневной лимит исчерпан | `daily_limit`, перенос на следующий день | `SCMS:2966-2969`, `SCMS:4195-4204` | для платёжного сценария нет теста; общий бюджет — `ScheduledDeliveryTransactionMySqlIntegrationTest.archiveOutboxReservationsShareTheOrdinaryDispatchBudget` |
| Интервал канала не выдержан | `rate_limited`, перенос | `SCMS:2977-2990` | `ScheduledDeliveryTransactionMySqlIntegrationTest.committedSentRetainsChannelGapAcrossInstancesWithWarmOldSettingsCache` |
| Тот же аккаунт занят кампанией | Автоответчик ждёт без отправки | `SCMS:3603-3634` | `ScheduledClientMessageServiceTest.autoresponderWaitsForACampaignSendOnTheSameWhatsAppAccountWithoutSending` |
| Первый счёт заказа доставлен | Заказ → «Выставлен счет», задача закрыта | `SCMS:3160-3207`, `SCMS:2693-2695` | `ScheduledClientMessageServiceTest.retryPaymentInvoiceSendsInvoiceAndCompletesStateWhenClientMessageSucceeds` |
| Менеджер вручную поставил «Выставлен счет» | Старая задача счёта закрыта, созданы напоминание и просрочка | `SCMS:1559-1587` | `ScheduledClientMessageServiceTest.manualInvoiceStatusImmediatelyReplacesObsoleteInvoiceRetryWithPaymentTasks` |
| Первое напоминание доставлено | Заказ → «Напоминание» | `SCMS:2696-2699`, `SCMS:3636-3645` | `ScheduledClientMessageServiceTest.paymentReminderMovesFirstInvoiceToReminderStatusAfterSend` |
| Срок заказа вышел, live-флаг выключен | Запись `payment_overdue_dry_run`, повтор через день | `SCMS:3487-3505` | нет теста |
| Срок заказа вышел, live-флаг включён | Заказ → целевой статус | `SCMS:3507-3518` | нет теста автоперевода; сам переход — `OrderStatusTransitionServiceTest.notPaidStatusSavesOrderBeforeCreatingBadReviewTasks` |
| Заказ в активном общем счёте | Одиночный счёт, напоминание и просрочка закрываются | `SCMS:3326-3341`, `SCMS:3473-3481` | `ScheduledClientMessageServiceTest.retryPaymentInvoiceCompletesWithoutSingleInvoiceWhenOrderIsLinkedToCommonBilling`, `.paymentReminderCompletesWithoutSingleReminderWhenOrderIsLinkedToCommonBilling`, `.paymentOverdueAutomationDefersToActiveCommonInvoice` |
| Заказ уже оплачен | Платёжное сообщение подавлено | `SCMS:3295-3306` | `ScheduledClientMessageServiceTest.paymentQueueIsSuppressedBeforeSendingWhenOrderIsAlreadySettled` |
| Заказ закрыт, задача осталась | Сверка закрывает платёжные задачи | `SCMS:1816-1835` | `ScheduledClientMessageServiceTest.reconciliationClosesActivePaymentAutomationForClosedOrders` |
| Идёт проверка ручной оплаты | Задачи не создаются и закрываются | `SCMS:1447-1449`, `SCMS:2954-2959`, `PIRS:409` | `ScheduledClientMessageServiceTest.reportedPaymentSuppressesInvoiceAndReminderReconciliationUntilReviewIsResolved`, `.oldActivePaymentQueueClosesWithoutPreparingBankDetailsWhenAReceiptWasReported`; `PaymentInvoiceRetrySchedulerTest.reportedReceiptPreventsEveryDirectInvoiceOrSanctionRearm` |
| Ручная оплата подтверждена | Платёжные задачи закрыты вместе с платежом | `PIRS:206-221` | `PaymentInvoiceRetrySchedulerTest.finalManualPaymentClosesActiveAndPausedPaymentAutomation`; `ManualSettlementReminderMySqlIntegrationTest.paymentFailureRollsBackReminderClosureAndSuccessfulRetryCommitsBoth` |
| Идёт восстановление отзывов | Напоминание отложено | `SCMS:3737-3756`; `CIDS:386-392` | `ScheduledClientMessageServiceTest.paymentReminderIsPausedWhileReviewRecoveryIsActive`; `CommonBillingServiceTest.dueReminderPostponesWhileReviewRecoveryIsActive` |
| Смена маршрута при сохранённом старом сообщении (заказ) | Отказ 409 | `PIRS:305-334` | `PaymentInvoiceRetrySchedulerTest.routeReplacementCannotLeaveOldPaymentInstructionsPending` |
| Смена маршрута общего счёта | Маркер `payment_route_changed_message_pending` | `CommonInvoicePaymentRouteWorkflow.java:367-370` | `CommonBillingServiceTest.commonInvoiceOwnerBankRouteCanBeReissuedForCurrentManagerProfileBeforePaymentStarts` |
| Сообщение о смене маршрута не доставлено | Повтор с предупреждением и при выключенных моментальных | `CIDS:263-265`, `CIDS:212-223`, `CIDS:526-539` | `CommonBillingServiceTest.failedRouteChangeMessageRetriesWithSafetyWarningEvenWhenImmediateMessagesAreDisabled` |
| Документы бумажного счёта доставлены | Заказ → «Выставлен счет», новый цикл напоминаний | `OSTS:189-205` | `OrderStatusTransitionServiceTest.newlyDeliveredBankDocumentsStartANewReminderCycleWhenAlreadyInvoiced`; `ScheduledClientMessageServiceTest.paperInvoiceDeliverySynchronizesThePaymentQueueInTheCallingTransaction` |
| Бумажный общий счёт не отмечен выданным | Напоминание отклонено | `CIDS:657-659` | `CommonBillingServiceTest.paperInvoiceReminderRequiresDocumentToBeMarkedAsIssued` |
| Напоминания общего счёта выключены | Планировщик ничего не делает | `CIDS:249` | `CommonBillingServiceTest.dueReminderSchedulerDoesNothingWhenOrdinaryPaymentRemindersAreDisabled` |
| Интервал напоминаний общего счёта | Берётся из `paymentReminderIntervalDays` | `CommonInvoiceSettlementService.java:2234-2245` | `CommonBillingServiceTest.commonInvoiceReminderDateUsesConfiguredOrdinaryPaymentInterval` |
| Срок общего счёта вышел, уведомление записано ≥ 2 дней назад | Счёт → `UNPAID`, заказы → «Не оплачено» | `CIOE:238-245`, `CIAW:116-126` | `CommonInvoiceOverdueEscalationTest.moveWaitsForTheOverduePeriodAndTwoDaysAfterThisCyclesNotice`, `.moveUsesTheManualUnpaidActionWithAFreshRecheckAndSkipsWhileSwitchedOff` |
| Любой из переключателей общего перевода выключен | Перевода и уведомлений нет | `CIOE:74-87` | `CommonInvoiceOverdueEscalationTest.everyAutoresponderSwitchMustAllowTheMove` |
| Пауза при общем счёте | Перевод откладывается, показанная дата сдвигается | `CIOE:156-177` | `CommonInvoiceOverdueEscalationTest.pausePostponesTheShownMoveAndSwitchedOffAutomationHidesIt` |
| Клиент сообщил об оплате, бумажный счёт не доставлен | Счёт не попадает в уведомления | `CIOE:251-262` | `CommonInvoiceOverdueEscalationTest.noticesListOnlyAutomaticRouteInvoicesAndStopWhileSwitchedOff` |
| Уведомление менеджеру не доставлено | Уведомление не записано, перевода нет | `ThematicStaffNotificationJob.java:104-116` | `ThematicStaffNotificationJobTest.managerGroupHearsAboutCommonInvoicesBeforeTheyMoveToUnpaidAndOnlyDeliveredNoticesCount` |
| Менеджер дал отсрочку | Дата перевода сдвигается | `CIOE:264-266` | `CommonInvoiceOverdueEscalationTest.theShownMoveDateIncludesTheExtension`; `FE/features/admin/common-billing/common-invoice-overdue-term.component.spec.ts` «saves a week of extension and shows who granted it» |
| Бумажный общий счёт: срок | От доставки документов плюс отсрочка | `CommonInvoiceOverdueClock.java:26-59` | `CommonInvoiceOverdueEscalationTest.aPaperInvoiceTermRunsFromTheDeliveryOfItsDocumentsPlusTheExtension` |
| Ручной «Не оплачено» общего счёта | Все неоплаченные заказы через бизнес-логику; при сбое одного статус счёта не меняется | `CIAW:197-237` | `CommonBillingServiceTest.markUnpaidMovesAllOpenItemsThroughOrderBusinessLogic`, `.markUnpaidDoesNotSetInvoiceUnpaidWhenAnyOrderTransitionFails` |
| Плохая задача выполнена (заказ) | Счёт с новой суммой сразу, при сбое — фоновый повтор | `BadReviewTaskServiceImpl.java:253-267` | `BadReviewTaskServiceImplTest.completeTaskSendsClientInvoiceWithDoneBadReviewSumWhenImmediateEnabled`, `.completeTaskSchedulesBadReviewInvoiceRetryWhenClientMessageFails` |
| Счёт после плохой задачи выключен настройкой | Задача отложена, не закрыта | `SCMS:2153-2157` | `ScheduledClientMessageServiceTest.disabledBadReviewScenarioPostponesDurableStateInsteadOfCompletingIt` |
| Счёт после плохой задачи: исход неизвестен | `UNKNOWN`, без повтора | `SCMS:2250-2277` | `ScheduledClientMessageServiceTest.badReviewDeliveryPersistsTokenAndPreservesAmbiguousOutcome` |
| Все плохие задачи выполнены, оплаты нет | Заказ → «Бан» | `SCMS:3343-3425` | `ScheduledClientMessageServiceTest.autoBanAfterBadReviewsMovesOrderToBanWhenFinalInvoiceStillUnpaid` |
| Автобан выключен | Задача остаётся активной | `SCMS:2943-2953` | `ScheduledClientMessageServiceTest.disabledAutoBanRemainsActiveForRetry` |
| Оплата пришла до автобана | Автобан отменён | `SCMS:3362-3366` | `ScheduledClientMessageServiceTest.settledPaymentCancelsAutoBanBeforeStatusMutation` |
| Плохая задача выполнена (общий счёт) | Сумма счёта обновлена, отдельный счёт не шлётся | `CIMem:300-352` | `BadReviewTaskServiceImplTest.completeTaskRefreshesCommonInvoiceInsteadOfSendingSingleBadReviewInvoice` |
| Счёт с новой суммой общего счёта задержан бюджетом | Сообщение сохранено и уходит через тот же бюджет | `CIMW:65-80` | `CommonInvoiceBadReviewDispatchTest.preservesNoticeDuringHoldAndResumesThroughTheSameBudget` |
| `UNPAID`: цикл плохих идёт / остановился | Причина для менеджера только при остановке | `CIAW:133-179` | `CommonBillingServiceTest.unpaidInvoiceNeedsNoManagerWhileItsBadReviewsRunOnSchedule`, `.overdueBadReviewsBringTheUnpaidInvoiceBack` |
| Неизвестный исход, истории чата нет | Остаётся `UNKNOWN`, повтора нет | `ScheduledDeliveryRecovery.java:60-68`; `CIDS:534-536` | `ScheduledDeliveryProtocolTest.manualRepairReportsUnavailableHistoryWithoutChangingUnknownState`; `CommonInvoiceBadReviewDispatchTest.missingHistoryForUnknownSendNeverRepeatsPaymentNotice` |
| Автоархив: история чата недоступна | Отложено, заказ не меняется | `SCMS:783-789` | `ReviewSilenceArchiveTest.missingOrUncertainChannelHistoryIsRecordedAndRetriedWithoutChangingTheOrder` |
| Автоархив при выключенных настройках | Не архивирует, цикл сохраняется | `SCMS:772-775` | `ReviewSilenceArchiveTest.autoresponderSettingsBlockArchiveAndResumeWithoutLosingTheCycle` |
| За 2 суток до просрочки заказа | Одна карточка менеджеру | `PaymentAttentionReminderService.java:25-43` | `PaymentAttentionReminderServiceTest.createsOneManagerCardForDeadlineWithinTwoDays` |
| Кнопка «Не оплачено» у менеджера (сайт, приложение) | Скрыта; у `OWNER`/`ADMIN` есть | `manager-board.config.ts:564-573`; `manager-board.helpers.ts:88-97` | `FE/features/manager/manager-board.config.spec.ts` «builds order actions and permits privileged publication exit without financial shortcuts»; `mobile/test/manager-board-helpers.test.mjs` «matches web manager bad-review order action flow» |
| Кнопка «Не оплачен» в общем счёте и на доске специалиста | Скрыта для не `OWNER`/`ADMIN` | `common-billing.component.ts:682-694`; `worker-board.component.ts:156-162`; `common-billing.page.ts:1477-1487` | нет теста |
| Менеджер шлёт «Не оплачено» прямым запросом | Сервер выполняет переход | `OrderStatusCommandService.java:36-62`; `CBAC:314-319` | нет теста на запрет (запрета нет) |
| Актуализация `payment-overdue` | Перевод до 200 заказов без проверки настроек автоответчика | `ClientMessageMaintenancePreviewService.java:121-156` | только `ClientMessageMaintenancePreviewServiceTest.statusChangingMaintenanceBatchesDoNotShareOneTransaction` |

## Не проверено / открытые вопросы

- **Ручная пауза.** В серверном коде `client.messages.paused-until` записывает только защита от массовых ошибок (`SCMS:4812-4816`); `PUT /settings/client-messages` этот ключ не пишет (`ADC:829-996`). Существует ли ручная постановка паузы вне этого (прямая правка `app_settings`, скрипты) — не проверено.
- **Возобновление после отклонённой проверки ручной оплаты.** Задачи закрываются `manual_payment_review` (`SCMS:2954-2959`), а сверка переоткрывает только закрытые задачи без отправок (`SCMS:1947-1963`). Кто заново ставит напоминание и просрочку заказу, у которого напоминания уже уходили, не прослежено.
- **Моментальный счёт после плохой задачи при выключенном worker или паузе.** По коду он не проверяет worker, паузу, окна и лимит (`SCMS:2032-2034`, `SCMS:2073`, `SCMS:2197-2202`); теста на это поведение не найдено, намеренность не подтверждена. То же для первого счёта общего счёта (`CIMW:69`).
- **Отправка новых реквизитов одиночного заказа** (`PaymentRouteChangeNotificationWorker.java:50-120`): обращений к настройкам автоответчика в этом классе и в `BE/p_products/status/service/OrderStatusNotificationService.java:149-180` не найдено; проверка live-режима глубже в `ClientChatMessageSender` и транспорте не прослежена.
- **Создание следующего заказа после оплаты** (`OrderTransactionServiceImpl.java:154`, `CommonInvoiceSettlementService.java:1873`, `BE/p_products/next_order/`): чтений настроек автоответчика в модуле `next_order` не найдено; сценарий целиком не разбирался.
- **Расхождения значений по умолчанию.** `paymentOverdueDays`: 30 в автоответчике (`SCMS:128`) и 60 как запасное значение актуализации (`ClientMessageMaintenancePreviewService.java:122-125`). `clientTextReminderIntervalDays`: 2 на сервере (`SCMS:118`) и 3 в форме (`admin-client-message-settings.facade.ts:81-84`). Фактические значения в рабочей БД не читались.
- **Просрочка одиночного заказа считается от входа в «Напоминание»**, а не от выставления счёта (`SCMS:1546-1548`, `SCMS:4700-4702`). Это следует из кода; подтверждения, что так задумано, и теста нет.
- **Физический архив заказов** (`archive.orders.*`, статусы «Архив», «Оплачено», «Бан»: `BE/archive/repository/OrderArchiveDryRunRepository.java:121-131`) — отдельный механизм хранения; его связь с настройками автоответчика не проверялась.
- **Мобильный экран настроек автоответчика** (`MOB/shared/mobile-dictionaries.component.ts`) не читался.
- **Ветка неуспеха `finishPaymentMessageSend`** (`CIDS:573-599`: «напоминание +1 день», запись `payment_route_changed_message_retry:`) из воркера вызывается только при успешной отправке (`CIDS:507-510`); достижима ли она из текущего кода — не подтверждено.
- **Локальный стенд и UI не запускались**: документ составлен только по чтению кода и тестов; тесты не выполнялись (машина занята длинным прогоном).
