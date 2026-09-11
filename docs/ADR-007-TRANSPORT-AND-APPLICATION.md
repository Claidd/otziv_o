# ADR-007. Общие сценарии для legacy MVC, REST и фоновых входов

Статус: принято. Дата: 2026-09-07. Владельцы: orders/management/messaging/performers.
Пункты: P16/P17.

Legacy MVC обслуживает формы и перенаправления, REST — web/mobile JSON. Они
сосуществуют ради действующих пользователей и поддерживаемых клиентов. Разные
формы и HTTP-ответы не требуют разных реализаций одного бизнес-перехода.

Transport adapter разбирает и валидирует вход, преобразует principal в явный actor,
вызывает application command и отображает результат в прежний HTTP/form contract.
Application command владеет проверкой текущей принадлежности, правами, транзакцией,
lock order, status/counters/waiting/publication dates и audit. Прямой вызов команды
из другого транспорта сохраняет эти проверки.

Worker, manager и owner могут иметь разные права на один переход. Общий сценарий
принимает проверяемый actor/context; manager-вход не получает права вызовом
worker-specific bypass, а пустой SecurityContext не означает системного пользователя.
Scheduler и Telegram используют те же application entry points там, где действие
совпадает. Другой бизнес-смысл остаётся отдельной командой даже при похожем URL.

Команды экспортируются явными module API/типами; controllers не становятся
межмодульным API. DTO отображения и транспортные исключения не должны управлять
денежной или order-транзакцией. Совместимые делегаты удаляются после перевода
callers и проверки HTTP/permissions/transaction regressions.

Проверка включает каждый разрешённый transport, чужой объект/роль, rollback,
позднюю смену исполнителя и однократный audit. Архитектурный gate обнаруживает
обращения к чужим внутренним классам и новые циклы даже через разрешённые API.
Существующие legacy exceptions имеют владельца в карте и удаляются вместе со связью.

Опора: [worker commands](WORKER_COMMAND_BOUNDARIES_2026-09-07.md),
[module ownership](ADR-001-MODULE-BOUNDARIES.md),
[manager scenarios](MANAGER_CONTROL_SCENARIO_BOUNDARIES.md).
Отвергнуты: перенос всего controller в один такой же god-service, копирование
orchestration вокруг общего нижележащего OrderService и унификация формы ценой
изменения публичного контракта. Пересмотр нужен при удалении transport или смене
модели полномочий.
