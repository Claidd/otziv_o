# ADR-005. API-контракты и общая клиентская логика

Статус: принято. Дата: 2026-09-07. Владельцы: API владельцы соответствующих domains,
web и mobile. Пункты: P19/P20/P16.

Ручные параллельные TypeScript DTO расходятся с фактическим Jackson JSON.
При этом устройства обновляются независимо, а новый backend может потребовать отката.
Одновременный выпуск всех потребителей не является допустимой предпосылкой.

Источник контракта — фактические backend handler/DTO и JSON serialization policy.
Версионированная OpenAPI-схема, DTO/operations и runtime fixtures генерируются
воспроизводимо. CI обнаруживает drift; generated files не правят вручную. В контракт
входят request/response, optional/null, IDs/деньги/даты/enums, ошибки, pagination и
HTTP permissions metadata. Метаданные прав документируют API, но не заменяют
серверную авторизацию и проверку объекта под блокировкой.

SDK остаётся независимым от транспорта. Angular clients используют существующие
HttpClient/interceptors, public capability и native networking. Generated fetch
не создаёт второй путь авторизации. Feature API получает самостоятельного владельца;
старый общий ApiService может временно делегировать, но не сохраняет вторую реализацию.
Изменяемое состояние редактора принадлежит экземпляру страницы, включая Ionic
leave/enter и reconciliation поздней записи.

Общий локальный versioned package устанавливается из clean checkout и Docker build
context. Два одинаковых pure helper имеют один source без Angular/Capacitor/DOM/
HTTP/storage. Отличающиеся routing rules сравниваются таблицей поведения и остаются
в адаптерах там, где различие обосновано.

Backend сначала расширяется совместимо. Fixtures привязываются к последнему
выпущенному и минимально поддерживаемому клиенту и к rollback backend. Отсутствие
сведений о выпущенной версии нельзя заменить придуманным номером synthetic fixture.
Неизвестный финансовый enum не разрешает опасный fallback. Удаление старого API
требует завершения окна поддержки и измерения его использования.

Действующий scope генератора и команды: [contracts README](../contracts/README.md).
Отвергнуты: ручное редактирование generated DTO, глобальный mutable UI service,
shared helper как замена серверных денежных checks и молчаливый новый fetch transport.
Пересмотр нужен при изменении wire semantics, package distribution или политики mobile support.
