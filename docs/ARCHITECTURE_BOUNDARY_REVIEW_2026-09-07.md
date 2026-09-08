# Проверка границ после P15/P17 — 7 сентября 2026

Проверены изменения байткода относительно ранее зафиксированных исключений.
Новые разрешения ниже относятся к перенесённым сценариям, а не к автоматическому
копированию наблюдаемого графа. Владелец исключения — исходный модуль; пересмотр
назначен на 2026-12-07. Основание — ADR-001, ADR-004 и ADR-007.

| Новый владелец | Перенесённые зависимости | Прежний сценарий и сохранённая граница |
|---|---|---|
| LeadWorkNotificationService | Manager, WhatsAppService | LeadServiceImpl: уже выбранный manager и отправка подготовленного уведомления; новая таблица manager не читается. Сохранённая generation/envelope до after-commit dispatch. |
| OrderStatusCommandService | Company, ScheduledClientMessageService, User, Worker, UserService, WorkerService | WorkerOrderCommands и соответствующие manager/MVC status handlers: actor, expected company и текущая принадлежность под канонической блокировкой Order; единый rollbackFor=Exception. |
| ReviewPublicationCommandService | WorkerCredentialPreparationScope, WorkerCredentialPreparationService | WorkerReviewPublicationCommands и manager publication entry: admission до короткой транзакции, прежние scope/authorization/cooldown. |
| ReviewPublicationMutationService | Company, WorkerActivityAction, WorkerActivityService | Worker/manager/legacy publication: повторная проверка принадлежности, mutation/counters и обязательный audit в общей заблокированной транзакции. |

Это 13 точных внутренних рёбер. Удалены 12 более не используемых разрешений:
LeadServiceImpl → WhatsAppService; ApiManagerOrderController → Order/OrderDetails;
WorkerOrderCommands → User/Worker/UserService/WorkerService;
OrderStatusNotificationService → PublicationProgressPreferenceService/MaxBotClient/
WhatsAppSendResult/WhatsAppOperationKey/WhatsAppService.
Зависимости старых классов, которые ещё используются другими сценариями, сохранены.

Отдельно экспортирован точный `p_products.controller.OrderCommandHttpAdapter`.
Это общий HTTP mapper исключений, не Spring controller и не бизнес-сервис:
он сохраняет исходный ResponseStatusException либо отображает statusCode
application exception. В нём нет проверки прав, repository, provider или транзакции.
Два потребителя — ApiManagerOrderController и ApiManagerReviewController —
сохраняют transport authorization и передают Authentication в публичные команды.
Весь controller package не экспортируется; gate по-прежнему запрещает
application/API зависеть от controller, поэтому экспорт не открывает такой обход.

Публичный `client_messages.api.ClientMessageDelivery` принимает неизменяемый
Target и два явно экспортированных DTO. JPA Company и транспортные реализации
не входят в этот контракт. Identity, encrypted WhatsApp envelope и Telegram/MAX
UNKNOWN barrier остаются у владельца messaging.

Итоговые ограничения: **3013** точных legacy внутренних рёбер, **309** repository
рёбер и **207** направленных рёбер существующих модульных циклов. Новых repository
зависимостей и циклических рёбер нет. Эти числа измеряют сохраняемый долг,
а не отсутствие циклов или завершение всей декомпозиции проекта.

Исходный read-only отчёт: `.codex-tmp/remediation-completion-20260907/p16-reviewed-diff.json`.
Gate сохраняет observed-файлы отдельно и отвергает как новые, так и устаревшие
разрешения. Отрицательные скомпилированные fixtures покрывают доступ к service,
array/generic-only ссылки на чужие типы и цикл через публичные интерфейсы.
