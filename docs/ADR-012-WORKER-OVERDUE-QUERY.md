# ADR-012: application query просрочек рабочего стола

Дата: 8 сентября 2026. Ограниченная backend-волна A09.

## Граница сценария

`GET /api/worker/overdue-orders` сохраняет путь, security annotation, endpoint metric и JSON-контракт `ManagerOverdueOrdersResponse`. Контроллер вызывает `WorkerOverdueOrdersQuery`, отображает независимый `WorkerOverdueOrders` в HTTP DTO и переводит существующую application-ошибку в прежний HTTP status. Query не импортирует controller, HTTP DTO, Servlet или Spring Web; отдельный архитектурный тест запрещает возврат этой зависимости.

`WorkerOverdueOrdersQuery` владеет cutoff, агрегированием шести секций, отбрасыванием пустых секций и подсчётом total/maxDays. Критерий остаётся прежним: thresholdDays=4, dueOnOrBefore=today−5. Неоплаченные и остальные неотображаемые статусы не добавляются в overdue payload. Секции сохраняют прежний порядок.

Общие функции загрузки reviews, recovery и bad tasks перенесены из последних перегрузок controller в `WorkerBoardTaskQueries`. Обычные страницы стола и overdue используют один набор этих функций. Перегрузки controller сохраняют выбор даты по сценарию, selected-worker filter, keyword, page size и направление сортировки; они делегируют самой загрузке. `WorkerStaffAccessPolicy.resolveWorker` содержит единственную прежнюю реализацию получения специалиста. ADMIN → OWNER → MANAGER → WORKER, owner managers, explicit selected worker и прежние NOT_FOUND ответы сохранены. Новых прав чтения не добавлено.

Устаревший и фактически неиспользуемый helper `toOverdueStatuses` удалён. Из controller удалены прямые зависимости на OrderRepository, UserService и WorkerService. HTTP mapping остаётся в controller; DTO менеджерского транспорта не переносится в application/query.

## Точная проверка перенесённых legacy dependencies

Владелец сценария до и после переноса — orders. Прежний источник всех приведённых обращений — `p_products.controller.ApiWorkerBoardController`. В соответствии с [ADR-001](ADR-001-MODULE-BOUNDARIES.md) проверены целые read-сценарии, сохранены прежние scope и транзакционные границы нижележащих сервисов; новый охватывающий запросы transaction не вводится.

В `WorkerBoardTaskQueries` перенесены ровно пять foreign-internal обращений:

- `bad_reviews.model.BadReviewTask` и `bad_reviews.service.BadReviewTaskService`;
- `review_recovery.model.ReviewRecoveryTask` и `review_recovery.service.ReviewRecoveryTaskService`;
- `u_users.model.Worker`.

В `WorkerOverdueOrdersQuery` перенесены только `BadReviewTask` и `ReviewRecoveryTask`: чтение scheduledDate из первой записи тех же bounded pages. Каждый target уже имел allowance прежнего controller. Остальные прежние controller→model/service рёбра ещё используются его другими сценариями и не удаляются фиктивно. Два действительно исчезнувших allowance controller→UserService/WorkerService удалены. Новые API exports, repository allowances и module-cycle edges не добавлены; baseline не заменяется observed-снимком. Это выделение ответственности, а не объявление всего межмодульного долга погашенным.

Точный результат этой A09-волны: **+7 reviewed read dependencies, −2 retired dependencies, net +5 class→class internals**. Семь новых строк разрешают прежнее scoped чтение в выделенных query; они не выдаются за семь полностью устранённых controller-обращений. Сам перенос не заявляет уменьшения общего internals baseline. Переход этих чтений на public API владельцев review-quality/identity — следующая отдельная волна.

## Доказательство и пределы

Существующие 60 `ApiWorkerBoardControllerTest` проверяют четыре роли, общий overdue cutoff, состав HTTP payload, selected-worker access, остальные страницы и команды. Они теперь используют реальные два query-сервиса. `WorkerOverdueOrdersQueryTest` независимо проверяет сумму, maxDays, стабильный порядок, неизменяемую коллекцию и остановку до запросов при отсутствии специалиста.

Сохраняется бюджет application reads на overdue: один order summary и ровно четыре pages с page=0,size=1 (два вида reviews, recovery, bad); identity lookups и нижележащие Page count-запросы не заменяются дополнительными fetch-all. Тест проверяет параметры и отсутствие дополнительных обращений к этим collaborator. Ранее существующая интерпретация `asc` для task sort сохранена, чтобы отделить этот перенос от изменения алгоритма выбора дат. Это не измерение SQL p95/p99 и не закрытие общей capacity/performance приёмки A09/F07. Остальные hotspots A09 остаются самостоятельной работой.
