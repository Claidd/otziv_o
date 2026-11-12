# Сценарные тесты счетов и оплат на настоящей базе
> Для ИИ-агентов. Состояние кода: рабочее дерево main на 2026-11-10.

Разделы 01–04 описывают правила. Этот раздел описывает набор тестов, который проверяет их целиком:
«выставить счёт → сменить получателя → отметить оплату» — и после каждого шага читает строки из базы.

Сокращения: `BT/` = `backend/src/test/java/com/hunt/otziv/`, `BE/` = `backend/src/main/java/com/hunt/otziv/`.

| Файл | Что это |
|---|---|
| `BT/payments/scenario/PaymentScenarioMatrixMySqlIntegrationTest.java` | Сам набор: один короткий тест на сценарий, номера в названиях совпадают с таблицей ниже |
| `BT/payments/scenario/PaymentScenarioWorld.java` | Посев: люди, компании, заказы, платёжные профили, связь общего счёта, включение LIVE; здесь же заглушка Т-Банка |

## Что такое стенд

- **Полный контекст приложения** (`@SpringBootTest`, профиль `test`) и **MySQL из Testcontainers** со всеми миграциями Flyway.
  Внешней тестовой транзакции нет: коммиты, блокировки строк и обработчики «после коммита» настоящие.
- **Шаги — вызовы рабочих сервисов**, тех же, что вызывают контроллеры и фоновые воркеры:
  `CommonBillingService` (счёт, окно оплаты, отметка оплаты, отклонение отчёта владельцу), `BankRecipientChanges`
  («Выдать номер карты» для общего счёта и заказа), `PaymentLinkService` (счёт заказа, ручная отметка),
  `CommonInvoiceMessageWorker.tick()` (доставка сообщения клиенту), `CommonInvoicePaymentNotificationDeliveryService`
  (outbox уведомлений), `CommonInvoicePaymentDisputeService`, `CommonInvoiceOverdueEscalation`.
  Собственного расчёта получателя, резерва или зачисления в тестах нет.
- **Заменены только внешние системы** (`@MockitoBean`): `TbankClient`, `TochkaClient`, `ClientChatMessageSender`
  (чат клиента), `TelegramService` (Telegram сотрудников). Заглушка Т-Банка (`PaymentScenarioWorld.FakeTbank`)
  отвечает идентификаторами из запроса, поэтому все рабочие сверки ответа банка остаются в силе.
- **Таймеры выключены**: перед первым сценарием набор останавливает планировщик (`ScheduledAnnotationBeanPostProcessor.destroy`,
  `ThreadPoolTaskScheduler.shutdown`), воркеры запускаются только из сценария. Одного удаления определения обработчика
  `@Scheduled` (`QuietBackground`) мало: без остановки фоновые задания шли рядом со сценариями и гонялись с ними за те же
  счета (доставка, сверка, восстановления, на мгновение выключающие выдачу реквизитов) — набор падал в случайных местах.
- **Очередь сообщений общая на класс**: перед каждым сценарием набор доставляет то, что оставили предыдущие, а шаг доставки
  ждёт сообщение, которое станет доступным в ближайшие секунды (часы JVM и контейнера базы — разные).
- **Часы как на бою**: JVM и MySQL работают в `Asia/Irkutsk` (на бою у приложения и базы `TZ=Asia/Irkutsk`).
  Платёжный код одновременно пользуется часами JVM (`LocalDate.now()` — день оплаты), часами базы (`NOW()`) и
  рабочими часами учёта (`ContractorPaymentBusinessClock`); на машине с другим поясом они расходятся на сутки.
  Пояс JVM возвращается после класса.
- **База общая на класс, мир — свой на сценарий.** Каждый тест создаёт собственного менеджера, команду, компании,
  заказы и связь; владелец и администратор общие (`scenario-owner`, `scenario-admin`). Сценарии не зависят
  друг от друга и от порядка запуска.

### Как включается LIVE (то, что делает `PaymentScenarioWorld.activateLiveRouting`)

Маршрутизация на сотрудников включена, только если открыты все ворота
`ContractorPaymentRuntimeSwitch.liveRoutingEnabled()` (`BE/contractor_payments/service/ContractorPaymentRuntimeSwitch.java:40-48`).
Стенд открывает их так же, как это делается на бою:

| Ворота | Чем открыты на стенде |
|---|---|
| Deployment-флаги `otziv.contractor-payments.live-routing-master-enabled`, `…reward-attribution-master-enabled` | Свойства `@SpringBootTest` |
| Настройки `contractor-payments.shadow-enabled`, `payments.tbank.enabled`, `payments.tbank.payment-links-enabled`, `client.messages.payment-instruction-source=BANK_LINK`, `client.messages.worker.enabled`, `client.messages.live.enabled`, `client.messages.immediate.enabled` | `AppSettingService.setBoolean/setString` — те же строки `app_settings`, что пишут экраны настроек |
| Новый учёт (необратимая граница на сегодняшнюю рабочую дату) | Команда владельца `ContractorPaymentSystemAdminService.activate` («ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ») |
| Выдача реквизитов сотрудников | Команда владельца `ContractorPaymentSystemAdminService.updateRouting` («ВКЛЮЧИТЬ РЕКВИЗИТЫ») |
| Терминал Т-Банка с ключами | Свойства `otziv.payments.tbank.*` (демо-терминал из миграции `V1_8_8`) |
| Шифрование снимков (`otziv.credential-encryption.active-key-base64`) | Тестовый ключ; без него очередь сообщений общего счёта не принимает сообщение |

Учёт фактического получателя («типизированная отметка оплаты») включается сам: после активации фаза учёта `LIVE`
(`BE/contractor_payments/service/ContractorActualPaymentAttributionService.java:125-148`).

### Что посев считает «исходным состоянием»

- **Доступный остаток («резерв»)** задаётся стартовым остатком профиля (`opening_balance_kopecks`): без начислений,
  корректировок и резервов это ровно то, что человек может принять
  (`BE/contractor_payments/service/ContractorPaymentProfileService.java:375-397`).
- **Профиль уже прошёл синхронизацию месяца**: `tracking_started_at` — первое число рабочего месяца. Включённый профиль
  моложе начала месяца — глобальный блокер маршрутизации
  (`BE/contractor_payments/service/ContractorCompletionRoutingReadinessService.java:54-59`).
- **Заказ выполнен**: один отзыв, опубликован сегодня исполнителем заказа, сумма состава равна сумме заказа.
  Без этого оплата не проходит: начисление требует подтверждённого выполнения
  (`BE/contractor_payments/service/ContractorCompletionRewardService.java:1087-1101`).
- **Команда менеджера** — пользователи с ролью `ROLE_WORKER`, связанные с менеджером в `managers_users`; владелец связан
  с менеджером так же (иначе он не видит его счета).
- **Связь** — `common_billing_accounts` + `common_billing_account_companies` + один счёт `READY` с готовыми позициями,
  автоповтор заказов выключен.

## Как запускать

Нужен Docker. Из `backend`:

```sh
./mvnw -o test -Dtest=PaymentScenarioMatrixMySqlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o test -Dtest='PaymentScenarioMatrixMySqlIntegrationTest#s05*' -Dsurefire.failIfNoSpecifiedTests=false   # один сценарий
```

Класс входит в обычный `./mvnw -o test` (имя заканчивается на `Test`), без Docker пропускается
(`@Testcontainers(disabledWithoutDocker = true)`). Результаты — `backend/target/surefire-reports/`.

## Сценарии — таблица: сценарий → что проверяется

Метод теста — `sNN_…` в `PaymentScenarioMatrixMySqlIntegrationTest`. «Строки» — что читается из базы после шага.

### Общий счёт: выставление

| № | Сценарий | Шаги (рабочие вызовы) | Что проверяется в базе |
|---|---|---|---|
| 01 | Один специалист на всех заказах, резерва хватает | `sendInvoice` → `CommonInvoiceMessageWorker.tick` | `common_invoices`: `INVOICED`, `MANUAL_MOBILE_BANK`, `CONTRACTOR_PAYMENT_PROFILE`, сумма маршрута, `sent_at`; `contractor_payment_allocations`: `LIVE`, `RESERVED`, `SPECIALIST`, `SPECIALIST_SELECTED`, `available_before_kopecks`; снимок номера = номер профиля; клиенту ушёл этот номер; заказы «Выставлен счет» |
| 02 | Специалист не подходит → менеджер (3 строки: мало резерва, допуск выключен, разные специалисты) | то же | резерв `RESERVED` у профиля менеджера, `MANAGER_SELECTED`, `specialist_rejection_reason` = `INSUFFICIENT_AVAILABLE_BALANCE` / `PROFILE_DISABLED` / `MIXED_SPECIALISTS` |
| 03 | Никто не подходит → владелец | `sendInvoice` → `tick` → клиент: `initPublicPayment` | маршрут `BANK_LINK`; резерв `OWNER_FALLBACK` без профиля, причины отказа специалиста и менеджера; до нажатия клиента Init в банк не вызывался; после — одна запись `common_invoice_payment_refs` `CURRENT`, `tbank_payment_id`, `payment_url` |

### Общий счёт: «Выдать номер карты»

| № | Сценарий | Шаги | Что проверяется в базе |
|---|---|---|---|
| 04 | Автоматический выбор на выставленном счёте (2 строки: тот же пул; «другой получатель») | `commonInvoicePaymentRouteChangeContext` → `BankRecipientChanges.invoice` → `tick` | прежний резерв `CANCELED` с `released_at`; новый `RESERVED`, `attempt_no=2`, `available_before_kopecks` = полный остаток (освобождённое не считается дважды); до доставки: счёт `READY`, `sent_at` пуст, операция `ROUTE_CHANGE`, сообщение в очереди `QUEUED`; после — `INVOICED`, клиенту ушёл новый номер |
| 05 | Названный получатель с остатком меньше счёта | `commonInvoiceRouteRecipients` → `BankRecipientChanges.invoice(recipientProfileId)` → второй счёт: `sendInvoice` | окно показывает превышение; резерв `RESERVED`, `RECIPIENT_CHOSEN_BY_MANAGER`, `available_before_kopecks` < суммы; второй счёт по заказу этого человека достаётся менеджеру, `specialist_rejection_reason=INSUFFICIENT_AVAILABLE_BALANCE` |
| 06 | Названный получатель, допуск выключен | `BankRecipientChanges.invoice(recipientProfileId)` | ответ 409; счёт, резерв, сообщение и очередь не изменились |
| 07а | Неоплаченная ссылка Т-Банка, автоматический выбор, принять некому | `initPublicPayment` → `BankRecipientChanges.invoice` | ответ 409; `Cancel` в банк не вызывался, сессия банка `NEW`; ссылка `CURRENT`; резерв `OWNER_FALLBACK`; задание смены `STOPPED` |
| 07б | Неоплаченная ссылка Т-Банка, названный получатель | то же с `recipientProfileId` → `tick` | `Cancel` вызван один раз; ссылка `CANCELED`; задание `DONE`; резерв владельца `CANCELED`; новый `RESERVED` у названного, `RECIPIENT_CHOSEN_BY_MANAGER`; у счёта нет `tbank_payment_id` |

### Общий счёт: «Отметить оплату»

| № | Сценарий | Шаги | Что проверяется в базе |
|---|---|---|---|
| 08 | Получателю из счёта | `manualPaymentOptions` → `markPaidWithAttributions` | счёт `PAID`, `paid_kopecks`, позиция оплачена, заказ «Оплачено»; резерв `CONFIRMED`, `confirmed_kopecks`; одна строка `contractor_actual_payment_attributions` (`LIVE`, без превышения); outbox: `CLIENT` + `RECIPIENT` |
| 09 | Другому кандидату списка (менеджеру, резерва не хватало) | то же | исходный резерв `CANCELED`; распределение `ACTUAL_PAYMENT` `CONFIRMED` у менеджера; строка поступления: исходный и фактический получатель, `available_before_kopecks` < суммы, `projected_overrun_kopecks` = сумма − остаток |
| 10а | Разовый «другой получатель»: человек команды с выключенным допуском, не из заказов счёта | `manualPaymentOtherRecipients` → `markPaidWithAttributions` | в обычном списке его нет, за кнопкой есть (`teamMember`); счёт `PAID`; поступление и `ACTUAL_PAYMENT` у него; получатель счёта не изменился |
| 10б | Профиль вне команды и вне компаний | `markPaidWithAttributions` | ответ 409; счёт не оплачен, резерв `RESERVED`, строк поступления и уведомлений нет |
| 11 | Менеджер сообщает «деньги получил владелец» (2 строки: владелец принимает; отклоняет) | `markPaidWithAttributions` (менеджер) → `manualPaymentOptions` (владелец) → повтор запроса либо `rejectOwnerReport` | `common_owner_receipt_approval`: ожидающая запись, счёт не оплачен; `personal_reminders` у владельца и администратора; принято → `PAID`, `ACCEPTED`, резерв `CANCELED`, поступление `OWNER`; отклонено → `REJECTED`, счёт не оплачен, напоминание автору; запросы решения сняты |
| 12а | Частичная сумма | `markPaidWithAttributions` | ответ 400; счёт, заказ, резерв не тронуты (откат всей транзакции) |
| 12б | Остаток разделён между двумя получателями | то же | счёт `PAID`; две строки поступления и два `ACTUAL_PAYMENT` `CONFIRMED` на свои суммы; резерв счёта `CANCELED`; outbox: `CLIENT` + два `RECIPIENT` |

### Счёт одного заказа

| № | Сценарий | Шаги | Что проверяется в базе |
|---|---|---|---|
| 13 | Выставление: специалист с резервом | `PaymentLinkService.createForOrderAuthorized` | `payment_links`: `WAITING_MANUAL_PAYMENT`, `MANUAL_MOBILE_BANK`, `CONTRACTOR_PAYMENT_PROFILE`; резерв `PAYMENT_LINK` `RESERVED` у специалиста; номер в тексте для клиента |
| 14 | «Выдать номер карты» названному получателю сверх резерва | `OrderBankRecipientChange.method(recipientProfileId)` | прежняя ссылка и резерв закрыты; новая ссылка и резерв `RESERVED`, `RECIPIENT_CHOSEN_BY_MANAGER`, `available_before_kopecks` < суммы |
| 15 | Ручная отметка оплаты другому получателю | `manualCardPaymentContextForOrder` → `submitManagerManualCardPaymentForOrder` | заказ «Оплачено»; исходный резерв `CANCELED`; ссылка `CONFIRMED` с фактическим получателем; строка поступления `PAYMENT_LINK`; `ACTUAL_PAYMENT` `CONFIRMED` |

### Уведомления и автоматика

| № | Сценарий | Шаги | Что проверяется в базе |
|---|---|---|---|
| 16 | Уведомление получателю и спор | `markPaidWithAttributions` → `retryPendingNotifications` → `CommonInvoicePaymentDisputeService.dispute` дважды | outbox `RECIPIENT` (получатель, сумма, строка поступления), после воркера `sent_at`, сообщение ушло в рабочую группу с кнопкой спора; спор: `disputed_at` один раз, напоминания менеджеру, владельцу, администратору; счёт остаётся `PAID`, резерв `CONFIRMED`; второе нажатие ничего не меняет |
| 17 | Просроченный общий счёт (2 строки: боевой перевод включён; выключен) | `ThematicStaffNotificationJob.dispatchCommonInvoiceOverdueNotices` → `CommonInvoiceOverdueEscalation.escalate` | включён: уведомление группе менеджера, `overdue_notice_at`; внутри срока уведомления счёт не двигается; после — счёт `UNPAID`, заказ «Не оплачено», позиция `unpaid`; выключен: уведомления нет, ничего не двигается |
| 17б | «Не оплачено» → выполнена плохая задача → счёт с новой суммой | `markUnpaid` → сумма заказа +200 ₽ (эффект плохой задачи посеян) → `repriceBadReviewInvoice` → `sendUnsentActionInvoices` → доставка сообщения | после «Не оплачено» резерв `RELEASED_UNPAID`; после пересчёта счёт остаётся `UNPAID` с новой суммой, `bad_review_notice_pending = false`; резервов два: `RELEASED_UNPAID` и новый `RESERVED` (попытка 2, новая сумма, тот же специалист); клиенту ушло одно сообщение. Получатель подобран без менеджера |

### «Выдать ссылку банка» и оплата через банк

| № | Сценарий | Шаги | Что проверяется в базе |
|---|---|---|---|
| 18 | Общий счёт: номер карты специалиста → ссылка банка владельца → оплата по ссылке | `CommonBillingService.changeCommonInvoicePaymentRoute` (цель `OWNER_TBANK`) → доставка сообщения → `initPublicPayment` (клиент нажал «Оплатить») → `PaymentLinkService.handleTbankWebhook` с подписанным уведомлением банка | резерв специалиста `CANCELED`; новый резерв `OWNER` / `OWNER_FALLBACK` с причиной `CLIENT_REQUEST_OWNER_TBANK`; маршрут `BANK_LINK`, счёт `INVOICED`, клиенту ушло «Способ оплаты изменён» без реквизитов специалиста; Init в банк — только после нажатия клиента; после уведомления счёт `PAID`, резерв `CONFIRMED` на всю сумму, заказ «Оплачено» |
| 19 | Счёт заказа: номер карты специалиста → ссылка банка владельца → оплата по ссылке | `PaymentRouteChangeService.change` (цель `OWNER_TBANK`) → `PaymentLinkService.init` → `handleTbankWebhook` | прежний счёт и его резерв закрыты; новая ссылка `CREATED` с резервом `OWNER` / `CLIENT_REQUEST_OWNER_TBANK`; строка в `payment_route_change_notification_outbox`; после уведомления ссылка `CONFIRMED`, резерв `CONFIRMED`, заказ «Оплачено» |

Уведомление банка формирует `PaymentScenarioWorld.FakeTbank.confirmed`: сессия становится `CONFIRMED`, поля подписываются
`TbankTokenSigner` паролем терминала из свойств теста — как подписывает настоящий банк. Применение подтверждённых оплат
(`payments.tbank.apply-confirmed-payments`) сценарий включает сам и возвращает обратно.
Сценарий 19 на время переключает банк в режим `LIVE` (терминал `ScenarioLiveTerminal` из свойств теста): оплата счёта
заказа через демо-терминал записывается как пробная (`TEST_CONFIRMED`) и заказ не переводит — так задумано в
`PaymentLinkSettlementService`. Общий счёт (сценарий 18) оплачивается и через демо-терминал.

## Как добавить сценарий

1. В тесте: `Person anna = world.specialist("…")`, `world.payable(anna, ContractorRole.SPECIALIST, остаток)`,
   `world.order(world.company("…"), anna, сумма, STATUS_PUBLISHED)`, `world.commonInvoice("…", заказы…)`.
2. Шаги — только через рабочие сервисы; пользователь задаётся `login(world.manager)` и `world.manager.auth()`.
3. Проверки — строки таблиц (`invoice(...)`, `reserve(...)`, `reserves(...)`, `receipts(...)`, `actualPaymentReserves(...)`,
   `openReminders(...)`), а не только ответ сервиса.
4. Если сценарий вскрыл расхождение с правилами разделов 01–04 — не подгоняйте проверку: пометьте тест `@Disabled`
   с причиной в одну строку и опишите находку.

## Не покрыто

| Что | Почему не покрыто |
|---|---|
| Платёжные задания (первый шаг цепочки выбора, зачёт в задание, возврат по заданию) | Отдельный механизм со своим учётом (`ManualPaymentTask*`); посев заданий не делался, цепочка в сценариях начинается со специалиста |
| Точка: ссылка, наблюдение без отмены, смена получателя, отметка оплаты при открытой ссылке | Заглушка есть только для Т-Банка; `TochkaClient` заменён пустой заглушкой, чтобы наружу не ушёл ни один запрос |
| Оплата по банковской ссылке: фоновая сверка без уведомления, частичная оплата, `PARTIALLY_PAID`, отказ и истёкший срок | Сценарии 18–19 покрывают только успешное подписанное уведомление банка на всю сумму |
| Бумажные счета (`OWNER_PAPER_INVOICE`), счёт и акт Точки | Свой набор шагов и документов; покрыт `PaperBillingSettlementMySqlIntegrationTest` и соседними тестами |
| Исправление ошибочного зачисления (`ManualReceiptCorrections`, ADMIN) | Не входило в заданный список; строки поступлений проверяются только на запись |
| Счёт одного заказа: запрос владельцу, спор получателя в течение 24 часов, клиентская кнопка «я оплатил» | Для заказа покрыты выставление, смена получателя и отметка другому получателю (13–15) |
| Автоответчик: пауза, рабочие окна, общий лимит, интервалы, напоминания об оплате, просрочка одиночного заказа | Покрыт только автоперевод просроченного общего счёта с боевым переключателем (17); остальное — модульные тесты раздела 04 |
| Плохие отзывы после «Не оплачено»: сами плохие задачи, банковская ссылка в цикле, автобан | Сценарий 17б покрывает пересчёт суммы, повторный подбор получателя и отправку счёта; создание и выполнение плохих задач — `BadReviewTaskServiceImplTest`, ожидание закрытия банковской ссылки — `CommonInvoiceBadReviewBankRepricing` без сценария |
| Запрет компании (`contractorPaymentRoutingEnabled=false`), признаки прежней оплаты, операционные пределы (сумма счёта, сутки) | Отдельные отказы цепочки; покрыты `ContractorPaymentLiveRoutingServiceTest` |
| Образование связи и готовность позиций рабочим путём (`attachOrderIfNeeded`, `completePublishedOrderIntoCommonInvoice`) | Счёт `READY` с готовыми позициями создаёт посев; сценарий начинается с «выставить» |
| Остаток как результат начислений | Посев задаёт остаток стартовым балансом профиля; начисления при оплате выполняются по-настоящему, но проверяются только через итоговые строки |
| Одновременные запросы (два менеджера, гонка за резерв) | Сценарии последовательные; блокировки покрыты `ContractorRoutingCanonicalLockOrderMySqlIntegrationTest` и соседними |
| HTTP-слой: `@PreAuthorize`, валидация тела запроса, сериализация | Шаги — вызовы сервисов, на которые ссылаются контроллеры; контракт клиентов проверяют `ClientApi*Test` |
| SHADOW-режим (до включения нового учёта) | База сценариев всегда приводится в состояние «LIVE включён»; включение необратимо |
