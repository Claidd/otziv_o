# Границы команд рабочего кабинета — P17/F19

В `ApiWorkerBoardController` все 29 HTTP-мутаций делегируются владельцам прикладных сценариев. Контроллер сохраняет маршруты, DTO входа/выхода, заголовки `no-store` для учётных данных и преобразование ошибки команды в исходный HTTP-статус. Семь неиспользуемых зависимостей контроллера удалены. Остаток контроллера — запросы доски, подборки и представление; его размер сам по себе не считается доказательством завершения декомпозиции.

| Владелец | Завершённые сценарии |
|---|---|
| `WorkerOrderCommands` | Статус, ожидание клиента, заметка заказа, заметка компании |
| `WorkerReviewAccountCommands` | Замена/деактивация аккаунта отзыва, переименование, удаление |
| `WorkerTaskEditingCommands` | Текст и разрешённое расписание плохой задачи / восстановления |
| `WorkerTaskAssignmentCommands` | Назначение специалиста в разрешённой команде менеджера/владельца |
| `WorkerTaskAccountCommands` | Замена/деактивация аккаунта плохой задачи / восстановления |
| `WorkerTaskCompletionCommands` | Завершение плохой задачи / восстановления |
| `WorkerReviewContentCommands` | Текст, ответ, заметка отзыва с проверкой принадлежности заказу |
| `WorkerReviewPublicationCommands` | Публикация и выгул с серверной подготовкой аккаунта и ограничениями доступа |
| `WorkerCredentialCommands` | Три сценария копирования и три сценария раскрытия учётных данных |

Общие правила имеют отдельных владельцев: `WorkerTaskSchedulePolicy`, `WorkerStaffAccessPolicy`, `WorkerReviewAccessPolicy`. Они сохраняют различие nullable-даты плохой задачи и восстановления, режим владельца `ALL_MANAGERS`, актуального менеджера заказа вместо устаревшего снимка менеджера задачи и серверное определение защищённого раздела отзыва. Прикладные пакеты не импортируют HTTP-контроллеры, servlet или Spring Web; это проверяется ArchUnit.

## Исполнитель и транзакции

Команды принимают неизменяемый `WorkerOrderActor` и проверяют роль перед обращением к данным. Проверенный `Authentication` передаётся до повторной проверки назначения внутри доменной транзакции. Старые методы доменных сервисов сохраняются для существующих вызовов; новые явные методы не наследуют отсутствие проверки из пустого или привилегированного `SecurityContext`.

Новые команды задач не создают общей внешней транзакции. Завершение плохой задачи сохраняет `NOT_SUPPORTED` для наблюдения у платёжного провайдера и отдельную транзакцию заблокированной записи. Замена аккаунта восстановления сохраняет `noRollbackFor = ResponseStatusException`: исключение отклонённого аккаунта фиксируется даже при отсутствии следующего аккаунта. Преобразование legacy-ошибки в `WorkerOrderCommandException` происходит после завершения доменного proxy через `WorkerLegacyFailureAdapter`; оно сохраняет код, reason и cause, включая 429/503. Обычная ошибка записи по-прежнему откатывает транзакцию.

Три раскрытия учётных данных сохраняют свои транзакционные границы. Строгий аудит остаётся обязательным до возврата секрета; обычный журнал действий сохраняет прежний порядок. Ограничение частоты смены аккаунта получает явного исполнителя, а повтор внутри HTTP-запроса допускается только для того же имени исполнителя.

## Отдельно исправленный дефект конкурентного доступа

На MySQL/InnoDB `REPEATABLE READ` первоначальный обычный запрос связи создавал снимок. После ожидания канонической блокировки заказа обычный `COUNT` мог подтвердить уже изменившееся назначение из старого снимка. Это независимо воспроизведено для плохой задачи, восстановления и отзыва; исходные журналы RED сохранены в ignored-каталоге доказательств.

После блокировки `Order` проверка использует текущие locking reads назначения и связи с заказом. `FOR UPDATE OF` ограничивает блокировки строками агрегата, без блокировок таблиц пользователей/специалистов. Для менеджера/владельца применяется актуальный manager ID заблокированного заказа через `ManagerAccessService.canAccessCurrentOrderManager`; режим `ALL_MANAGERS` сохраняет доступ к существующему заказу без назначенного менеджера. Глобальный уровень изоляции не изменён.

Смена исполнителя, менеджера или связи с заказом после первоначальной проверки отклоняет запись. Разрешение изменения даты также повторно проверяется по заблокированной задаче: поздний запрос работника не возвращает прежнюю дату после изменения менеджером. Это проверено отдельно для плохой задачи и восстановления, при сохранении их разных nullable-date контрактов. Внешняя платёжная операция не выполняется под новой общей транзакцией, а callback завершения не обходит повторную проверку назначения.

## Проверка

Проверки используют реальные команды и политики, реальные Spring transaction interceptors, отдельный MySQL из закреплённого образа и JDBC-адаптеры репозиториев. В набор входят роли всех 21 новых входов, отсутствие записей при отказе, HTTP-коды 401/404/409/422/429/500/503, nullable-даты, подмена раздела, сохранение исключения аккаунта при 409, откат поздней обычной ошибки, внешний провайдер вне транзакции, изменение назначения между snapshot/read-lock/write и фактический SQL из аннотаций репозитория.

Первый срез до последней сборки: 146/146 PASS (59 controller, 32 новых command-проверки, 54 прежних task-проверки, ArchUnit transport boundary). MySQL proof: исходный 7-case набор — 2 воспроизводимых RR FAIL; после исправления — 7/7 PASS. Следующий срез — 33/33 PASS, включая 15 MySQL cases и реальное выполнение SQL: четыре ownership-запроса, три current-binding-запроса, отсутствие лишних user/worker locks. Финальный совмещённый срез — **251/251 PASS, 0 skipped**, Java 26, 2026-09-07 09:53:52 UTC (`worker-p17-final-targeted-v2.log`). В нём 20 task MySQL cases, 11 review MySQL cases, старые controller/task/manager проверки, новые прикладные проверки и транспортное правило ArchUnit. После последней правки HTTP-адаптера отдельный прогон — **61/61 PASS**, 09:56:09 UTC (`worker-p17-http-final.log`): 60 controller tests, включая настоящий Spring MVC ответ 429 с `WORKER_ACCOUNT_ACTION_COOLDOWN`, `Retry-After` и состоянием таймера; плюс ArchUnit. Эти наборы пересекаются, складывать их счётчики нельзя.

Последняя правка HTTP-адаптера сохраняет исходный подкласс legacy-ошибки после завершения доменного proxy. Тем самым сохраняются специализированные exception handlers, их заголовки и структура тела ответа. Код, тесты и прикладные границы зафиксированы; общий `clean verify` и запуск полного приложения выполняются корневой задачей по отдельному финальному снимку.

Журналы находятся в `.codex-tmp/architecture-remediation-20260907/`. Поведение locking reads сверено с [документацией MySQL 8.4](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html) и проверено прямым выполнением настоящих `@Query` на MySQL; использованный синтаксис ограничения таблиц — [SELECT / FOR UPDATE OF](https://dev.mysql.com/doc/refman/8.4/en/select.html).

Повтор: Java 26, `./mvnw.cmd -B -ntp -Dtest=ApiWorkerBoardControllerTest,WorkerRemainingCommandsTest,WorkerTaskCommandsMySqlIntegrationTest,WorkerOrderCommandsMySqlIntegrationTest,WorkerAssignmentMutationGuardServiceTest,WorkerAssignmentMutationGuardRepositoryContractTest,WorkerReviewMutationActorMySqlIntegrationTest,ManagerAccessServiceTest test`. Для MySQL-наборов необходим Docker; команда не обращается к VPS и не выполняет restore/deploy.


## Завершение общих status/publication входов, 2026-09-07

`OrderStatusCommands` и `ReviewPublicationCommands` в `p_products.api` объединяют worker board, manager API и старые MVC/form маршруты. HTTP-адаптеры сохраняют DTO, поля форм, redirects, flash messages и status codes. Worker waiting/gates не перенесены вслепую на manager: owner явно различает EntryPoint, а mutation pipeline использует общий canonical Order lock, актуальную принадлежность, counter/date rules и explicit actor audit. Legacy публикация сохраняет свои flowgate redirects; manager остаётся без worker-only подготовки, а права/родительский binding проверяются внутри TX.

Status owner `OrderStatusCommandService` использует rollbackFor=Exception. `ReviewPublicationCommandService` выполняет admission/gates, `ReviewPublicationMutationService` — транзакционную публикацию и журнал действий. Domain `OrderService`/`OrderStatusTransitionService` сохраняют legacy overloads и новые overloads с явным Authentication. Новые application owners не импортируют Spring Web; `OrderCommandHttpAdapter` отображает ошибку после завершения proxy, сохраняя прежний subtype/cause.

Новые scoped evidence: `shared-order-commands-targeted-v2.log` —192/192 PASS (2026-09-07 11:39:45 UTC); `completion-causal-targeted-v2.log` —99/99 PASS (11:54:07 UTC), включая11 real MySQL shared publication cases и9 shared status cases. Positive controls, чужой explicit actor при ambient ADMIN, expected parent mismatch, поздний rollback counter/date/audit и ошибка activity после domain write проверены на реальных Spring proxies/JDBC MySQL adapters. Журналы находятся в `.codex-tmp/remediation-completion-20260907/`; наборы пересекаются.
