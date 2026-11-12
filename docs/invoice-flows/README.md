# Счета и оплаты: сводка для ИИ-агентов

> Состояние кода: `main` на 2026-11-11.
> Перед изменением любого платёжного сценария прочитайте этот файл и раздел по своей теме.
> Правила проекта — в `AGENTS.md`; этот каталог их не заменяет, а делает конкретными для счетов.

## Как читать

| Файл | О чём | Когда открывать |
|---|---|---|
| [01-issuing-and-recipient-selection.md](01-issuing-and-recipient-selection.md) | Выставление счёта заказа и общего счёта, порядок выбора получателя, расчёт резерва, что уходит клиенту | Меняете выставление, маршрутизацию, тексты счёта |
| [02-route-change-bank-links-paper.md](02-route-change-bank-links-paper.md) | Смена получателя и способа оплаты, отмена и замена банковских ссылок, счёт и акт Точки | Меняете кнопки «Выдать номер карты», «Выдать ссылку банка», бумажные счета |
| [03-payment-marking-reserves-notifications.md](03-payment-marking-reserves-notifications.md) | Отметка оплаты, перерасчёт резервов, исправление зачислений, уведомления и спор получателя | Меняете окно «Отметить оплату», учёт поступлений, уведомления |
| [04-autoresponder-and-invoices.md](04-autoresponder-and-invoices.md) | Что автоответчик делает со счетами: напоминания, срок оплаты, «Не оплачено», переключатели и лимиты | Меняете любую фоновую отправку или автоматический перевод статуса |
| [05-scenario-tests.md](05-scenario-tests.md) | Сценарные тесты на настоящей базе: «выставить → сменить → оплатить», что покрыто и что нет | Меняете платёжный сценарий — добавьте или поправьте сценарий |
| [06-local-payment-sandbox.md](06-local-payment-sandbox.md) | Песочница оплат на локальном стенде: боевые пути кода с банком-заглушкой и перехватом клиентских сообщений | Проверяете любой платёжный сценарий на стенде; меняете обмен с банком |

Связанные документы: `docs/FINANCIAL_SCENARIO_BOUNDARIES.md` (кто владеет каким сценарием и транзакцией),
`docs/CONTRACTOR_PAYMENT_ROUTING_RUNBOOK.md` (правила маршрутизации, расчёт остатка, shadow/live),
`docs/TOCHKA_PAPER_INVOICES.md`, `docs/ADR-003-MONEY-AND-TRANSACTIONS.md`, `docs/ADR-001-MODULE-BOUNDARIES.md`,
`contracts/README.md` (контракт клиентов).

## Словарь

| Слово в интерфейсе | В коде | Смысл |
|---|---|---|
| Счёт заказа | `PaymentLink` (`payments`) | Счёт одного заказа: банковская ссылка, номер карты или бумажный счёт |
| Общий счёт | `CommonInvoice` (`common_billing`) | Один счёт на несколько заказов и компаний одной связи |
| Получатель | `ContractorPaymentAllocation.recipientProfile`, тип `OWNER` / `MANAGER` / `SPECIALIST` | Кому клиент переводит деньги |
| Платёжный профиль | `ContractorPaymentProfile` | Реквизиты сотрудника и его допуск: `enabled` (расчёт) и `liveEnabled` (можно выдавать клиентам) |
| Резерв, доступный лимит | `ManualPaymentTaskContractorCapacityService.ordinaryAvailable` | Сколько сотрудник ещё может принять от клиентов: остаток обязательств компании перед ним минус уже выданные счета |
| Резерв под счёт | `ContractorPaymentAllocation` в статусе `RESERVED` | Сумма счёта, закреплённая за получателем до оплаты |
| Платёжное задание | `ManualPaymentTask*` (`payments`) | Номер карты с лимитом, заведённый владельцем; проверяется раньше сотрудников |
| Банковская ссылка | `paymentRouteType` `TBANK_LINK` / `TOCHKA_LINK` / `BANK_LINK` | Оплата картой или по СБП; оплату подтверждает банк |
| Номер карты | `MANUAL_MOBILE_BANK`, `MANUAL_EXTERNAL_LINK` | Перевод по реквизитам; оплату отмечает менеджер |
| Счёт и акт Точки | `InvoicePaymentMode.OWNER_PAPER_INVOICE`, `paper_billing` | Бумажный счёт для юрлиц; оплата проверяется в банке |
| Автоответчик | `client_messages` (`ScheduledClientMessageService`) | Фоновые сообщения клиентам и автоматические переводы статусов |

## Правила, общие для всех разделов

1. **Счёт оплачивается целиком.** Частичная ручная оплата не принимается; сумму можно только разделить между несколькими
   получателями так, чтобы вышел весь остаток (раздел 03).
2. **Клиент не меняет способ оплаты сам.** Только менеджер по его просьбе и только пока оплаты нет (раздел 02).
3. **Автоматический выбор получателя:** платёжное задание → специалист заказа (для общего счёта — только если он один
   на все заказы) → менеджер → владелец. Всю команду автоподбор не перебирает (раздел 01).
4. **Ручной выбор получателя** (окно «Выдать номер карты», добавлено 2026-11-10): менеджер может назвать любого из
   пула — специалисты заказов, менеджер, команда менеджера, работавшие с теми же компаниями. Остаток резерва при этом
   не проверяется; все остальные предохранители остаются: активность, роль, допуск `enabled` + `liveEnabled`, полные
   реквизиты, операционные пределы, запрет компании, отсутствие прежних оплат по счёту.
   Код: `ContractorPaymentLiveRoutingService.reserveChosenContractorForCommonInvoice` / `…ForPaymentLink`,
   причина решения `RECIPIENT_CHOSEN_BY_MANAGER`.
5. **Сверх резерва** можно: (а) зафиксировать уже пришедшие деньги (отметка оплаты), (б) выдать номер карты человеку,
   которого назвал менеджер. Автоподбор сверх резерва не назначает никогда, и человек с отрицательным остатком в
   автоподбор не попадает, пока остаток не восстановится (`ordinaryAvailable` не бывает меньше нуля).
6. **Резерв прежнего получателя снимается до выдачи нового.** При смене маршрута —
   `releaseCommonInvoiceRouteForReplacement` / `releaseClosedPaymentLink`; при отметке оплаты другому человеку —
   `releaseOriginal` (статус `CANCELED`) и новое распределение `ACTUAL_PAYMENT` (раздел 03).
7. **Перед необратимой отменой в банке — репетиция.** `BankRecipientChanges.checkCapacity` выполняет всю замену в
   транзакции с откатом; если получателя нет, ссылка остаётся действующей. У Точки отмены нет: новые реквизиты
   выдаются сразу, прежняя ссылка наблюдается (раздел 02).
8. **Деньги владельцу подтверждает владелец или администратор.** Отметка менеджера «получил владелец» становится
   запросом (`common_owner_receipt_approval`) и уведомлением владельцам и администраторам (раздел 03).
   **Карта владельца — только там, где её разрешает банковский профиль менеджера** (`OwnerCardPolicy.allows`:
   политика «карта до лимита, затем банк» и заполненные реквизиты; добавлено 2026-11-12, для счёта заказа и общего счёта).
   Там владельца можно выбрать в «Отметить оплату» и при счёте-ссылке банка — как запрос владельцу (у заказа —
   кнопка в Telegram, у общего счёта — подтверждение в окне; раздел 03), а «Выдать номер карты» выдаёт карту владельца после сотрудников или по выбору менеджера, пока
   остаток месячного лимита карты вмещает счёт (раздел 02). Владелец с профилем «только банк» (ИП) не
   предлагается и не подставляется нигде.
9. **«Не оплачено» ставит автоответчик.** Ручная кнопка в интерфейсах показана только владельцу и администратору;
   сервер ролью этот переход не ограничивает. Автоперевод работает только при включённом
   `client.messages.payment-overdue.live-enabled` (раздел 04). На бою он включён: в копии базы от 2026-11-10
   заказы переводятся автоматически (последний — 10.11.2026), общим счетам рассылаются предупреждения о сроке.
   Значение по умолчанию в коде и на локальном стенде — выключено: стенд выключает его сам при восстановлении базы.
10. **Всё фоновое — через переключатели автоответчика:** пауза, рабочие окна, общий лимит, интервалы; разрешение
    перечитывается перед изменением заказа и перед внешней отправкой (`AGENTS.md`, раздел 04).
11. **Границы модулей проверяются тестами.** Новая связь с чужим внутренним классом или репозиторием роняет
    `ModuleEncapsulationTest` / `ModuleBoundaryTest`; пользуйтесь пакетами `*.api` и уже разрешёнными классами.
12. **Контракт клиентов генерируется.** После изменения запросов и ответов под `/api/manager/`, `/api/common-billing/`,
    `/api/payments/` выполните переэкспорт (см. «Как проверять»).
13. **Время ручной оплаты общего счёта ставит сервер**, а не устройство сотрудника: часовой пояс и неверные часы
    компьютера или телефона на запись не влияют; повтор запроса и решение владельца сохраняют уже записанное время
    (раздел 03). Время на экране и в запросах клиенты считают в рабочем поясе `Asia/Irkutsk` (`shared/business-date.ts`).

## Что где менять

| Задача | Сервер | Сайт | Приложение |
|---|---|---|---|
| Порядок и правила выбора получателя | `contractor_payments/service/ContractorPaymentLiveRoutingService.java` | — | — |
| Смена маршрута общего счёта | `common_billing/service/CommonInvoicePaymentRouteWorkflow.java`, `BankRecipientChanges.java` | `features/admin/common-billing/common-billing.component.*`, `shared/payment-recipient-picker.component.ts` | `mobile/src/app/features/common-billing.page.ts` |
| Смена маршрута заказа | `payments/service/PaymentRouteReplacementWorkflow.java`, `PaymentRouteChangeService.java`, `OrderRecipientRouteAdapter.java` | `features/manager/manager-order-edit-modal.component.*`, `card-number-route-target.ts` | `mobile/src/app/features/order-details*` |
| Отметка оплаты общего счёта | `common_billing/service/CommonInvoiceManualPaymentWorkflow.java`, `CommonManualPaymentAttributionCoordinator.java`, `CommonOwnerReceiptApprovals.java` | `features/admin/common-billing/common-manual-payment-attribution-modal.component.*` | диалог `mobile-common-manual-payment-dialog` |
| Состав общего счёта: исключить и вернуть заказ | `common_billing/service/CommonInvoiceMembershipWorkflow.java` (`detachOrder`, `returnOrder`, `returnableOrders`) | `features/admin/common-billing/common-invoice-return-orders.component.ts`, `common-billing.component.*` | `shared/common-invoice-return-orders.ts`, `features/common-billing.page.ts` (блок «Состав») |
| Подключение компании к общему счёту: что собирается, что остаётся на своём счёте, пояснения менеджеру | `common_billing/service/CommonBillingCompanyReconciliationWorkflow.java`, `CommonInvoiceInitializationService.java` (`keepsOrderOnItsOwnInvoice`), `CommonBillingLinkNotes.java` | `features/manager/manager-company-edit-modal.component.*` (блок «Общий счёт») | `features/manager/manager-company-billing.facade.ts`, `features/manager.page.ts` |
| Учёт поступлений и резервов | `contractor_payments/service/ContractorActualPaymentAttributionService.java`, `ContractorPaymentAccountingService.java` | — | — |
| Сводка «Кому зачтена оплата» за месяц (в т. ч. владелец для «Счёта и акта») | `payments/service/ManualPaymentRecipientMonthlySummaryService.java` | `features/admin/tbank-payments/tbank-payments.component.*` | `features/tbank.page.ts` |
| Уведомления и спор | `payments/service/ManualPaymentRecipientTelegramNotificationService.java`, `CommonInvoicePaymentDisputeService.java`, `ManualCardPaymentReviewNotificationService.java` | — | — |
| Автоответчик | `client_messages/service/ScheduledClientMessageService.java`, `common_billing/service/CommonInvoiceOverdueEscalation.java` | `features/admin/dictionaries/*` | — |
| Инструкции для менеджеров: шаги на снимках экрана | — | `features/admin/common-billing/common-invoice-guide.component.ts` (окно и инструкция общего счёта), `features/manager/order-payment-guide.ts` (заказ), снимки `public/assets/guide/{light,dark}`, размеры `guide-shots.ts`; пересъёмка — `infrastructure/scripts/local/guide-shots/README.md` | — |

## Как проверять

Из `backend` (нужен Docker для тестов с MySQL):

```sh
./mvnw -o test -Dtest='ContractorPaymentLiveRoutingServiceTest,CommonBillingServiceTest,CommonManualPaymentAttributionCoordinatorTest,PaymentLinkServiceTest,ModuleEncapsulationTest,ModuleBoundaryTest'
./mvnw -o test    # полный набор перед выпуском
```

Контракт клиентов после изменения API:

```sh
./mvnw -o -Dtest=ClientApiContractExportTest,ClientApiJacksonFixtureTest,ClientApiMvcContractTest -Dclient.contract.export=true test
node contracts/generate.mjs && node contracts/generate.mjs --check
node --test contracts/client-common.test.mjs
```

Сайт и приложение:

```sh
npm test --prefix frontend -- --watch=false
npm run test:unit --prefix mobile
```

Локальный стенд обязателен для рабочих сценариев (`AGENTS.md`): `infrastructure/scripts/local/prod-like-smoke.ps1`,
сайт `http://localhost:8088`. На обычном стенде допуски сотрудников выключены, реквизиты стёрты, банки и отправки
отключены — выдача номера карты и ссылки банка там отказывает. Для платёжных сценариев включите песочницу:
`./infrastructure/scripts/local/payment-sandbox.ps1 on` (раздел 06). В ней работают выбор получателя, резервы,
ссылки Т‑Банка и Точки, оплата «за клиента» и автоответчик; страница управления — `http://localhost:8099`.
Проверяйте сам сценарий в интерфейсе — подтверждение, отмену и результат в базе
(`contractor_payment_allocations`, `contractor_actual_payment_attributions`, `common_invoices`).

## Чего ещё нет (на 2026-11-11)

- Окно выбора получателя подключено на сайте к общему счёту и к окну заказа
  (`features/manager/order-payment-route-windows.component.ts`, инструкция — `order-payment-guide.ts`) и в приложении
  (`mobile/src/app/shared/mobile-payment-recipient-picker.component.ts`, общие функции —
  `mobile/src/app/shared/payment-recipient-picker.ts`). В приложении окно проверено тестами и в браузере с
  настоящим сервером стенда при ширине телефона (общий счёт и заказ: отмена, поиск, выбор сверх резерва, отказ
  автоподбора, выдача, резерв в базе, сообщение клиенту); на самом устройстве — нет.
- Сценарный набор на настоящей базе есть (раздел 05, `PaymentScenarioMatrixMySqlIntegrationTest`): выставление,
  смена получателя, ссылка банка с оплатой, отметка оплаты, уведомления, просрочка. Чего в нём нет — таблица
  «Не покрыто» раздела 05 (платёжные задания, Точка, бумажные счета, фоновая сверка банка, автоответчик целиком).
- В приложении окно отметки оплаты общего счёта отстаёт от сайта (нет «Другой получатель», обязательны галочки);
  см. раздел 03, «Права» и расхождения сайта и приложения.

## Так задумано, а не пробел

- **Пометка об отправке в `lastError` общего счёта — ожидание, а не ошибка.** Пока сообщение клиенту
  отправляется (стоит в очереди, ждёт повтора или сервер выясняет исход), сервер держит там
  `message_send_in_progress` или `payment_route_changed_message_in_progress` (`CommonInvoiceDeliveryService`);
  счёт на это время в статусе `READY`. Сайт и приложение не печатают этот код: показывают текст
  `commonInvoiceDeliveryWait` (`shared/client-common/src/billing-payments.ts`) обычной строкой — на сайте над
  подсказкой этапа и в «Истории счёта» как «Сообщение клиенту», в приложении в строке под маршрутом. Блок
  «Проблема общего счета» для этих пометок не открывается (`commonInvoiceProblemCode`), подсказка «Счет готов к
  отправке… не завис ли он» тоже (`commonInvoiceStatusProblem`). Если операция доставки уже не в очереди, а
  её исход неизвестен или отправка остановлена (`delivery.status` `UNKNOWN`, `FAILED`), вместо «ещё
  отправляется» показывается текст этой операции («Исход отправки уточняется. Повторная рассылка
  заблокирована.»): пометка может держаться, пока операция не разобрана. Текст исход не обещает: если отправка не
  удалась или зависла, сервер заменяет пометку на `delivery_failed` / `message_send_stale`, и они, как и
  остальные значения `lastError`, показываются как ошибки.
- **Получателя при первом выставлении счёта выбирает система** — по резервам сотрудников (раздел 01). Ручной
  выбор в этот момент не нужен: сменить получателя можно сразу после, кнопкой «Выдать номер карты».
- **Ручной перевод в «Не оплачено» сервер ролью не ограничивает.** Кнопка скрыта от менеджеров в интерфейсах,
  админу и владельцу она показывается — на случай, если автоответчик не отработает. При необходимости перевод
  остаётся доступным и менеджеру; запрета на сервере нет намеренно.
