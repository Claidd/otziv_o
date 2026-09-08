# Финансовые сценарии и транзакционные границы

Срез реализации P18 от 2026-09-07. Модуль остаётся в одном приложении и одной
MySQL: атомарные операции не заменены асинхронными событиями. Старые публичные
методы остаются совместимыми входами; перенесённые методы делегируют единственной
реализации сценария.

| Ответственность | Владелец | Транзакция / внешний эффект |
|---|---|---|
| Применение подтверждённой оплаты, возврат, webhook и состав счёта | `CommonInvoiceSettlementService`, API `CommonInvoicePaymentOperations` | REQUIRED для применения; REQUIRES_NEW для возврата; прежний TransactionTemplate для webhook; сохраняются порядок locks и повторная проверка принадлежности |
| Подготовка, выполнение и фиксация банковского Init | `CommonInvoiceInitializationService` | Подготовка и фиксация в отдельных прежних write-транзакциях; запрос к банку между ними; frozen identity и uncertain outcome сохраняются |
| Отмена архивной T-Bank попытки | `CommonInvoiceCancellationService` | Claim под locks → внешний Cancel → fenced/state-aware завершение; прежние лимиты попыток и ручная проверка |
| Сверка и возврат попыток Точки | `CommonInvoiceTochkaReconciliationService` | Durable claim возврата до POST; после неопределённого результата только наблюдение/ручная проверка, без нового Refund POST |
| Ручная оплата, атрибуция и бумажный счёт | `CommonInvoiceManualPaymentWorkflow` | Прежние REQUIRED/NOT_SUPPORTED boundaries; банковская сверка вне write TX; evidence и денежное применение фиксируются атомарно |
| Смена frozen маршрута и способа оплаты | `CommonInvoicePaymentRouteWorkflow` | NOT_SUPPORTED orchestration; prepare и finish — отдельные прежние write-транзакции, bank observation между ними |
| Состав счёта, изменение суммы и готовность позиций | `CommonInvoiceMembershipWorkflow` | Прежние Order → Account → Invoice locks; проверка standalone evidence, atomic attach/detach и after-commit readiness |
| Настройка общего плательщика и компаний | `CommonBillingAccountWorkflow` | Сохранение, проверки видимости и прежняя нормализация account views в исходных транзакциях |
| Сверка включённых компаний | `CommonBillingCompanyReconciliationWorkflow` | Persistent generation/claim/lease, bounded batch, canonical locks и прежний after-commit запуск |
| Панель общих счетов | `CommonInvoiceBoardWorkflow` | Сохраняет write-before-read нормализацию дублей и сумм; filtering/count/pagination не объявляются чистым чтением |
| Архив, бан и неоплата | `CommonInvoiceArchiveWorkflow` | Recovery/evidence checks и связанные изменения заказов остаются атомарными |
| Одобрение отзывов общего счёта | `CommonInvoiceReviewApprovalWorkflow` | Сначала валидация всех позиций, затем одобрение в общей транзакции |
| Решения оператора по спорному состоянию | `CommonInvoiceRecoveryWorkflow` | Evidence fences; TLS recovery сохраняет REQUIRES_NEW, отдельная сверка standalone — NOT_SUPPORTED; UNKNOWN не становится доказательством отсутствия оплаты |
| Публичная capability и сообщение клиента об оплате | `CommonInvoiceCheckoutWorkflow` | Прежние token/recipient/route checks; anonymous contract сохранён |
| Удаление счёта с заказами | `CommonInvoiceDeletionWorkflow` | Одна исходная транзакция, guards до удаления и повторная проверка состава |
| Подтверждение отдельной позиции | `CommonInvoicePositionPaymentWorkflow` | Атрибуция, закрытие заказа и итоги счёта фиксируются вместе |
| Счёт/напоминание клиенту и сообщение об оплате | `CommonInvoiceDeliveryService` | Подготовка и завершение отделены от сети; прежний delivery contract сохранён |
| Детали, доказательства платежа и карточки следующего цикла | `CommonInvoiceDetailsAssembler` | Сборка DTO из прочитанных данных. Не вызывает initialization/delivery/manual workflows |
| Summary и отображение маршрута | `CommonInvoicePresenter` | Прежние вычисления и чтение реквизитов |
| Независимые значения | `CommonInvoiceRouteState`, `CommonInvoicePaymentIdentity` | Чистые предикаты, совместимость provider identity, маскирование и криптографические токены без зависимости от workflow |
| Наблюдение отдельной банковской попытки | `PaymentBankObservationService` | Ограниченные запросы к банкам; применение результата остаётся в транзакционном владельце |
| Выбор источника оплаты | `CommonInvoiceRouteSelector` | Сохраняет проверку доступности и прежние транзакционные границы выбора/фиксации |
| Public/manager/admin представление отдельной ссылки | `PaymentLinkPresenter` | DTO, статус доступности, текст инструкции, отображение профиля |
| Отмена и возврат отдельной ссылки | `PaymentLinkCancellationWorkflow` | NOT_SUPPORTED coordinator, независимые prepare/apply, MANDATORY shared transitions под уже взятыми locks; UNKNOWN блокирует повтор POST |
| Применение банковского результата отдельной ссылки | `PaymentLinkSettlementService` | MANDATORY lock-scoped transitions, прежние prepayment overload boundaries, order/ledger/outbox в общей транзакции |
| Сумма отдельного платежа | `PaymentLinkAmountPolicy` | Единый расчёт с учётом завершённых задач по плохим отзывам |
| Жизненный цикл и допустимость отдельной попытки | `PaymentLinkLifecycleService` | Общие проверки суммы, состояния и provider kind; mutating helpers присоединяются к уже открытой транзакции |
| Ручное подтверждение и подтверждение источника | `ManualPaymentConfirmationWorkflow` | Канонические Order → Link locks, атрибуция и применение денег в одной транзакции; внешние уведомления после commit |
| Подготовка ссылки и бумажной инструкции | `PaymentLinkPreparationWorkflow` | Авторизация под Order lock, frozen route и generation; замена из другого сценария использует MANDATORY seam без новой транзакции |
| Сохранённое состояние банковского Init | `BankInitializationStateService` | Lease, неизменная identity попытки и сохранение UNKNOWN; expiry lease не доказывает отсутствие платежа |
| Init и SBP отдельной ссылки | `PaymentLinkInitializationWorkflow` | NOT_SUPPORTED orchestration: committed reservation → банк вне TX → locked fenced finish; UNKNOWN не запускает повторный POST |
| Разрешение публичного токена | `PublicPaymentLinkResolutionService` | Проверка capability и поиск допустимой текущей попытки; replacement остаётся у preparation |
| Правила ручного перевода | `ManualCardRoutePolicy` | Проверки исторического маршрута, получателя, повторного подтверждения и допустимого перехода |
| Применение банковского наблюдения | `BankObservationApplicationService` | MANDATORY transitions без HTTP; outcome применяется под locks вызывающего сценария |
| Ручной перевод при открытой банковской ссылке | `ManualCardPaymentWorkflow` | Наблюдение и при необходимости Cancel вне TX; recipient/evidence fences и применение подтверждения в locked finish |
| Одобрение владельцем ручного перевода | `OwnerManualCardApprovalWorkflow` | Durable approval и callback token, порядок Order → Link → Approval; token/binding повторно проверяются после locks |
| Webhook и фоновая сверка отдельной ссылки | `BankPaymentReconciliationWorkflow` | Верификация до применения, provider I/O вне TX; failed apply сохраняет доказательство и quarantine в отдельной транзакции |
| Согласование платежа с заказом | `OrderPaymentLinkWorkflow` | Изменение суммы, завершение и expiry под прежними locks; evidence не переписывается новым payable |
| Публичная страница и сообщение об оплате | `PublicPaymentPageWorkflow` | GET может применить наблюдение или разрешить retired attempt; provider I/O вне locked фаз, поэтому это не read-only query |
| Замена маршрута отдельной ссылки | `PaymentRouteReplacementWorkflow` | Проверка прав и expected identity, Order → Link scope и прежняя атомарная замена |
| Административная панель отдельных ссылок | `PaymentLinkAdminBoardWorkflow` | Сохраняет expiry старых ручных ссылок перед выборкой, права, фильтры и архивные проекции; обычная write TX сохранена |

## Направление вызовов

`CommonBillingService` и `PaymentLinkService` больше не зависят друг от друга,
в том числе через `ObjectProvider`. Операции между модулями идут через два узких API:

- `CommonInvoicePaymentOperations`: отдельный платёж применяет/возвращает оплату
  в общем счёте через settlement.
- `StandalonePaymentOperations`: общий счёт запрашивает сверку или отмену
  отдельной попытки у модуля payments. Полный payment facade в API не экспортируется.

`ModuleBoundaryTest` запрещает восстановление обеих прямых связей и обратные
зависимости выделенных финансовых collaborators на старые facades. API не может
зависеть от repository или service implementation. Reviewed baseline чужих
repositories отражает перенос уже существовавших lock/query accesses, а не
разрешение добавлять новые обходы владельцев данных.

## Что проверять при следующем переносе

1. Переносить весь сценарий вместе с propagation, lock order, snapshots и
   проверкой повторной команды; учитывать self-invocation и Spring proxy.
2. Сохранять банковский запрос между prepare/finish, если так устроен исходный
   сценарий. Не добавлять внешний запрос в write TX ради короткого facade.
3. Проверять повтор webhook, конфликт маршрута, частичный/неопределённый ответ,
   конкурентную смену состава и rollback атрибуции/заказа вместе с оплатой.
4. Запускать прежние финансовые проверки через реальные collaborators с mock
   банков, соответствующие MySQL tests и штатный prod-like startup/smoke.
5. Сравнивать фактический query count/latency затронутого пути. Сохранённые SQL
   тела и зелёный unit suite не заменяют нагрузочный замер.

Отдельный `CommonInvoiceAfterCommitSender` сохраняет прежнюю best-effort отправку
после commit. Он не является durable outbox и не участвует в денежной атомарности.
Существующие legacy отправители без сохранённого operation ID не приобретают
гарантию дедупликации от одного переноса в новый класс.

## Оставшаяся миграция

Объём классов не является критерием закрытия F07. Billing facade сохраняет
совместимые делегирующие входы и небольшие membership/evidence reads. Lifecycle,
account, board normalization, recovery и public checkout имеют отдельных владельцев.
Совместимые `ManagerBoardPage`/`ManagerBoardMetrics` преобразуются на границе facade
без новых запросов к БД; новые workflows не зависят от типов старого facade.
Полный общий срез после этих переносов проверяется отдельно: 343 tests и настоящий
Spring startup подтверждены для membership/account/reconciliation, но не подменяют
проверку более поздних board/archive/recovery/checkout/deletion/position изменений.

В `PaymentLinkService` завершён перенос перечисленных выше сценариев. Совместимый
facade содержит 420 строк, 46 публичных методов и четыре публичных record:
`PaymentRouteReplacement`, `OwnerManualCardPaymentApprovalOutcome`,
`PaymentInstructionPreparation`, `PaymentLinkReconcileResult`. AST-проверка среза
до декомпозиции подтверждает сохранение сигнатур, типов результата, модификаторов,
аннотаций публичных методов и полных деклараций этих record. Конструктор DI изменён
вместе с тестовой сборкой реальных collaborators. Десять private delegates пока
сохранены для существующих reflection tests; длинных бизнес-потоков в facade нет.

Последняя платёжная волна перенесла 137 тел методов без изменения алгоритмов.
Изолированный Java 26 прогон 2026-09-07 в 09:21:47 UTC подтвердил все 296 платёжных,
транзакционных и full-Spring/MySQL проверок, включая 240 прежних сценариев и шесть
новых конкурентных проверок callback одобрения. Четыре архитектурные проверки
также прошли. Единственный из 301 результатов, не прошедший в этой копии, — общий
repository ratchet: копия содержит старый `ManagerControlService`, а reviewed
baseline уже отражает его более позднюю декомпозицию. Новых платёжных обходов
владельцев данных в этом отчёте нет. Общий финальный срез проверяется отдельно;
этот результат не объявляется успешным полным `verify` всего проекта.

Локальные воспроизводимые доказательства сохранены в
`.codex-tmp/architecture-remediation-20260907/payment-final-public-api-compatibility.txt`
и `windows-payment-final-targeted-v2.log` в том же каталоге. Переименование
административного владельца из Queries в BoardWorkflow отдельно сохраняет его
исходную write-before-read семантику; алгоритмы и транзакции от имени класса не меняются.
После переименования чистая Java 26 компиляция и все 240 прежних платёжных тестов
повторно прошли в 09:25:26 UTC; лог `windows-payment-admin-board-rename.log`.

Некоторые исторические методы загрузки (`invoice`, `adminLinks`, части board)
также пересчитывают/сохраняют суммы или закрывают старые записи. Это команды с
ответом, а не чистые queries. Декомпозиция не меняет их молча на read-only.
Владелец постепенного удаления совместимых входов — billing/payments; удалить
вход можно после перевода callers и сохранения контрактных/транзакционных проверок.
