# ADR-008. Публичные API уведомления о пояснении специалиста

Статус: принято для A01, 2026-09-08.

`WorkerRiskExplanationNotificationService` больше не получает чужие ORM-модели
или полные сервисы identity, management и messaging. Сценарий остаётся внутри
activity, а правила выбора получателей и работа с данными принадлежат владельцам.

| API | Владелец и реализация | Контракт |
|---|---|---|
| `u_users.api.WorkerRiskReviewerDirectory` | identity; `WorkerRiskReviewerDirectoryService` | По worker ID возвращает неизменяемые `Reviewer(userId, telegramChatId)`. Сначала активные назначенные менеджеры, затем OWNER и ADMIN, без дублей и самого специалиста. Associations читаются в транзакции identity, JPA-объекты не экспортируются. |
| `personal_reminders.api.SystemReminderCommands` | management; существующий `PersonalReminderService` | Команда с recipient ID и скалярными source/title/text/order данными. Владелец повторно получает активного пользователя; существующее открытое напоминание сохраняется, иначе создаётся прежнее due-now напоминание. Присоединяется к текущей транзакции REQUIRED. |
| `t_telegrambot.api.TelegramNotifications` | messaging; `TelegramNotificationService` | Отправка обычного текста по выбранному chat ID с прежним результатом/повторами `TelegramService`. API не экспортирует bot implementation. Это best-effort notification, не новая durable-delivery гарантия. |

Единственный новый потребитель — уже авторизованный обработчик принятого пояснения
в `worker_activity`. Проверка принадлежности инцидента и полномочий специалиста
остаётся в callback-сценарии до фиксации пояснения. Эти API — внутренние application
возможности, не HTTP endpoints и не самостоятельное разрешение произвольному
пользователю выбирать адресата/source. Новый внешний transport должен выполнить
собственную проверку доступа до вызова сценария.

`Notification(workerUserId, incidentId, orderId, text)` по-прежнему передаётся
после успешного commit исходной транзакции. Координатор уведомлений сохраняет
`REQUIRES_NEW`: чтение получателей выполняется в новом persistence context,
недоступном исходному detached User из Telegram. Identity query присоединяется
к этой транзакции, возвращая только scalar snapshots. Ошибка уведомления или
commit новой транзакции не отменяет принятого пояснения; rollback исходного
сценария не отправляет ложное сообщение об успехе. Новая API-команда сохраняет
прежнюю проверку открытого reminder; этот перенос не заявляет новой гарантии
дедупликации при двух конкурентных независимых уведомлениях.

Экспортированы ровно три интерфейса (вложенные записи относятся к внешнему типу
при анализе policy). Пакеты services/models/controllers не экспортируются.
Новые реализации обращаются только к внутренним типам собственного владельца;
в `PersonalReminderService` используется уже существующая зависимость identity,
без нового class→foreign-internals ребра. Логические направления activity →
identity/management/messaging уже существовали и не меняются. Из baseline удалено
только более не используемое `WorkerRiskTelegramCallbackService → Manager`;
исключения для пяти новых прежних зависимостей NotificationService не добавлены.

Проверки: `ModuleEncapsulationTest`, `ModuleBoundaryTest`,
`WorkerRiskReviewerDirectoryServiceTest`, `PersonalReminderServiceTest`,
`WorkerRiskExplanationNotificationServiceTest`, `WorkerRiskTelegramCallbackServiceTest`
и `WorkerRiskTelegramCallbackTransactionMySqlIntegrationTest`. Последний сохраняет
реальные MySQL commit/rollback, detached lazy collection и разные EntityManager
для приёма пояснения и поиска получателей; в нём исполняются реальные новые
projection/notification adapters и scalar reminder command. Сетевая доставка
остаётся mock. До выполнения проверки не считать этот список результатом PASS.
