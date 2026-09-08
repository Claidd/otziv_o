# ADR-001. Владение данными и направление зависимостей

Статус: принято для новых и изменяемых сценариев, 2026-09-07.
Контекст: P16–P18 из архитектурного плана. Система остаётся модульным монолитом
с одной MySQL. Перенос в микросервисы не является условием исправления.

## Владельцы

Полная проверяемая карта пакетов находится в
`backend/src/test/resources/architecture/module-owners.properties`.
Это логические владельцы данных, а не заявление, что каждый существующий пакет
уже является изолированным модулем. Любой новый корневой пакет требует записи.

| Владелец | Данные и решения | Вход для изменений |
|---|---|---|
| orders | заказ, отзывы, принадлежность исполнителю, статус и счётчики | application commands / существующие сервисы переходов |
| companies | компания, филиал, привязки организации | CompanyRecordOperations / CompanyStatisticsOperations и сценарии компании |
| identity | локальный пользователь, роли, epoch, session bindings и revocation | пользовательские security commands |
| billing | общий счёт, его состав, платёжные ссылки общего счёта | CommonInvoicePaymentOperations / invoice lifecycle |
| payments | отдельная платёжная попытка, наблюдение банка, маршрут | PaymentLinkService; внутренние observation/route collaborators |
| contractor-ledger | распределения, фактический получатель, ledger | contractor application services |
| performers | профиль исполнителя, предложение, назначение, delivery intent | PerformerAssignmentService с canonical locks |
| leads | лид и исходящая команда его синхронизации | LeadCommandService и сервис изменения лида |
| messaging | запланированное сообщение и результат доставки | sender API со стабильным operation ID |
| management / reporting | контрольные сценарии и представления | workflow изменяет через владельца; query возвращает DTO |
| остальные | каталог, хранилище, аудит, зарплата, интеграции | назначены в полной карте; действуют те же правила |

Общий ledger не становится общей writable-моделью для всех модулей.
Межмодульные команды используют API владельца. Для атомарного изменения заказа
и счёта допустима одна локальная транзакция с согласованными блокировками;
владение транзакцией определяется сценарием, а не HTTP-контроллером.
Reporting получает read projection. Прямой доступ к чужому repository является
долгом даже тогда, когда исторически используется только SELECT.

## Проверки зависимостей

`ModuleBoundaryTest` использует ArchUnit 1.4.2, поддерживающий bytecode Java 26.
Он запускается обычным `./mvnw verify` и проверяет:

1. `CommonInvoiceSettlementService` не зависит от CommonBillingService или
   PaymentLinkService; PaymentLinkService и CommonBillingService не зависят друг от друга.
2. Новые order application classes не зависят от controller, Servlet или Spring Web.
Invoice API не зависит от repository/service implementation.
3. Межмодульные зависимости на repository не могут увеличиваться без явного
   изменения reviewed baseline. Правило анализирует bytecode, включая generics
   и скрытый ранее ObjectProvider, а не только текст import.

Первичный baseline содержит 264 существующие зависимости class→repository.
Это зафиксированный долг, не разрешение на новые обходы. При выделении класса
перенос старого доступа требует записи причины в review: прежний владелец,
сценарий, сохраняемая транзакция и целевой API. В частности, settlement пока
координирует order/payment repositories в прежнем денежном сценарии. Устранение
этих доступов требует проверки lock order и atomicity, а не замены import.

CI никогда не создаёт baseline и не обновляет его автоматически. Диагностический
`target/architecture/cross-module-persistence-observed.txt` только показывает
фактические связи. Удалённые связи следует удалять из baseline при изменении
сценария. Владельцем исключения является исходный модуль из карты. Следующий
пересмотр исключений — 2026-12-07; продление требует причины.

## Компания в финансовой транзакции

`CompanyRecordOperations` реализован независимым `CompanyRecordService`, который
зависит только от repositories своего модуля. Он сохраняет прежние lookup, write
и Company → CompanyInfo порядок вместе с обратной связью. Транзакционный `save`
присоединяется к исходному денежному сценарию; старый CompanyService делегирует
этой же реализации. Пять order/payment consumers используют узкий API, сохраняя
прежние тела методов и порядок запросов.

Отдельный `CompanyStatisticsOperations` предоставляет прежнюю месячную выборку
для PaymentCheck, не создавая зависимости на сценарии редактирования компании.
Это устраняет найденный полным Spring startup косвенный цикл через отзывы,
CommonBilling и PaymentLink. Новые API/реализации не используют Lazy/provider
для сокрытия этого цикла. Проверки сохранения/ошибок, старые caller tests,
ArchUnit и настоящий Spring startup прошли вместе (91 tests).

## Финансовые границы и совместимость

PaymentLinkService вызывает синхронный `CommonInvoicePaymentOperations`.
Единственная реализация — CommonInvoiceSettlementService. В ней сохранены
проверка суммы/идентичности банка, последовательность locks, повтор webhook,
классификация наблюдений и атомарное применение результата.
`applyConfirmedOrderPayment` сохраняет REQUIRED,
`applyStandalonePaymentReversal` — REQUIRES_NEW,
webhook — прежние явные TransactionTemplate boundaries.

Обратный сценарий общего счёта использует `StandalonePaymentOperations`:
сверка/отмена отдельной попытки. Полный payment facade и ObjectProvider больше
не нужны в CommonBillingService. Подробный каталог —
[FINANCIAL_SCENARIO_BOUNDARIES.md](FINANCIAL_SCENARIO_BOUNDARIES.md).

PaymentBankObservationService выполняет ограниченные запросы к банкам и
возвращает наблюдения; применение выполняется после повторного чтения и lock.
CommonInvoiceRouteSelector выбирает и фиксирует маршрут с прежними checks и
транзакцией. Они не вызывают PaymentLinkService обратно.

CommonBillingService сохраняет совместимые методы-делегаты для старых callers.
В них нет второй реализации правил. Условие удаления: callers конкретного
сценария переведены на его API и внешние DTO/транзакционные тесты сохранены.
Владелец этой миграции — billing/payments.

CommonInvoiceAfterCommitSender передаёт только прежнюю best-effort отправку после
commit синхронному Spring listener. Денежные изменения событиями не заменены.
Rollback не отправляет сообщение; failure после commit не откатывает деньги.
Это не durable delivery: существующую гарантию нельзя объявлять outbox-гарантией.
Для гарантированной доставки требуется отдельная запись intent с replay policy.

## Дополнение P16: инкапсуляция и циклы

`ModuleEncapsulationTest` дополняет repository-ratchet проверкой всех зависимостей
между логическими владельцами на внутренние классы. Экспортируемые API перечислены
в `module-public-api.txt`: допустим отдельный точный тип или конкретный API package,
но не весь набор services/controllers/models. Существующие snapshot-only сервисы
outbox/workload health и их DTO экспортированы для monitoring: у сервисов нет
мутационных методов, а чтение выполняется по bounded cache вне scrape request.

Начальная база содержит 3025 legacy class dependencies и 207 направленных рёбер,
участвующих в существующих циклах логических модулей. Она получена из сохранённого
проверенного source snapshot с manifest SHA-256
`a94a943b7919c215cf6f9e9e2c26dfdf7777130449c4724a9a431731f87bdb1b`.
Это более широкий набор, чем repository baseline: числа не складываются.
Владелец исключения определяется source module, пересмотр — 2026-12-07.

Gate запрещает новый доступ к внутреннему классу, новое циклическое ребро и
сохранение больше не используемого разрешения. Анализ ведётся по bytecode, включая
типы полей/параметров/generics; циклы считаются и для разрешённых public API.
Новая ацикличная зависимость на явно экспортированный API допускается.
`security.credentials.CredentialCipher` экспортирован как точная инфраструктурная
возможность шифрования: archive и WhatsApp сохраняют собственные данные, а cipher
не даёт доступа к чужой бизнес-таблице. Старое отдельное разрешение archive → cipher
удаляется из baseline. Новые `p_products.api` задают общий контракт изменения
статуса и публикации для worker, manager API и legacy MVC; ограничения транспорта
и доступа к repositories/services применяются к этим интерфейсам явно.
Тестовые скомпилированные fixtures проверяют запрещённый service access и цикл
через public interface. CI пишет только observed reports в target и никогда
не переносит их автоматически в baseline. Перенос существующего исключения
требует прежнего/нового владельца, полного сценария и сохранённого TX/authorization
обоснования; добавление новых бизнес-обходов не маскируется как перенос.

После P15/P17 проведена [отдельная проверка каждого изменения границ](ARCHITECTURE_BOUNDARY_REVIEW_2026-09-07.md):
13 перенесённых зависимостей, 12 удалённых разрешений и точный transport-only
export OrderCommandHttpAdapter. Текущая база — 3013 внутренних, 309 repository
и прежние 207 циклических рёбер. Все 12 архитектурных проверок прошли.

Решения по другим границам: [сессии](ADR-002-SESSION-SECURITY.md),
[деньги](ADR-003-MONEY-AND-TRANSACTIONS.md), [delivery](ADR-004-DELIVERY-AND-RECOVERY.md),
[клиентские контракты](ADR-005-CLIENT-CONTRACTS.md),
[выпуск/recovery](ADR-006-DEPLOYMENT-AND-RECOVERY.md),
[MVC/REST/application](ADR-007-TRANSPORT-AND-APPLICATION.md).

## Пределы доказательства

Baseline не доказывает отсутствие всех транзитивных циклов старого приложения,
а выделение нескольких сценариев не означает завершения всей декомпозиции.
Измеренные бизнес latency/SQL comparisons должны прикладываться к каждому
дальнейшему изменению горячего пути. Существующие тесты финансовых сценариев
работают через реальные новые collaborators с mock банков/repositories;
полное Spring startup и MySQL suites проверяются отдельно.

Источник версии инструмента: https://github.com/TNG/ArchUnit/releases/tag/v1.4.2
