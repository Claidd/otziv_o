**План устранения архитектурных замечаний otziv**

Дата: 7 сентября 2026 года. Основание: [архитектурный аудит F01–F20](E:/Works/Projects/otziv/docs/ARCHITECTURE_AUDIT_2026-09-07.md).

Статус: исходный согласованный план. Реализация выполняется; актуальные изменения,
результаты проверок и оставшиеся условия выпуска перечислены в
[рабочем статусе](ARCHITECTURE_IMPLEMENTATION_STATUS_2026-09-07.md).
Ниже сохранены исходные требования и критерии приёмки, включая production-проверки.
Названия компонентов в плане были предложениями; окончательные владельцы указаны
в каталогах сценариев. План охватывает все 20 замечаний и подготовку окружения,
которой не хватало для полного backend-аудита.

**Цель и порядок**

Сначала устранить ошибки, способные менять не ту сущность, терять задания или создавать лишние отправки; параллельно обеспечить восстановление и сузить эксплуатационные риски. Затем постепенно разделить бизнес-сценарии и закрепить границы модулей автоматическими проверками. Целевая архитектура — управляемый модульный монолит с отдельными интеграционными процессами.

Работы организованы в 22 пакета P00–P21. Пакет — законченный результат, иногда требующий нескольких небольших pull request. Размер PR определяется единством сценария и возможностью проверить/откатить изменение, а не лимитом строк.

Три направления можно вести параллельно: backend и интеграции; web/mobile; инфраструктура и безопасность. Это роли, а не предположение о фактическом размере команды. Если исполнитель один, внутри каждого направления сохраняется указанная последовательность. Наличие PR не означает закрытие замечания: для некоторых пунктов нужны миграция существующих данных, выпуск клиента или операционная проверка.

| Пакет | Результат | Замечания | Основная роль | Зависимость |
|---|---|---|---|---|
| P00 | Воспроизводимое окружение и актуальная исходная точка | Подготовка, F16 | Backend + CI | Начать первым |
| P01 | Защищённая сессия редактора и mobile runtime tests | F01, часть F16 | Mobile | P00 |
| P02 | Корректные состояния mobile auth | F12 | Mobile | Runtime основа P01 |
| P03 | Корректный payload VPS retry | F02 | Backend | P00 |
| P04 | Продвигающаяся retry-очередь и разбор старых записей | F03, завершение F02 | Backend | P03 |
| P05 | Лимит фактически выполняемых задач | F11 | Node/backend | P00 |
| P06 | Исправленные зависимости и повторяемый audit | F14 | Владельцы четырёх Node-проектов | P00; container-проверка совместно с P10 |
| P07 | Полное и проверенное восстановление системы | F04 | Эксплуатация | Инвентаризация начинается сразу |
| P08 | Ограниченный доступ мониторинга к Docker | F05 | Эксплуатация | P00/Docker-стенд |
| P09 | Внешнее обнаружение отказов и старых backup | F17 | Эксплуатация | P07 для итоговых backup-сигналов |
| P10 | Проверяемая readiness Chromium/OCR | F18 | Node + CI | P05 для lifecycle; P06 для итогового образа |
| P11 | Завершённый протокол отзыва сессий/JWT | F13 | Backend + Keycloak | P07 перед production-изменениями; P02 для клиентской совместимости |
| P12 | Атомарные переходы заданий исполнителей | F09 | Backend | P00 |
| P13 | Доставка предложений и уведомлений с явным исходом | F08, F20 | Backend | P12 |
| P14 | Durable исходящая синхронизация лидов | Часть F10 | Backend + владелец принимающего приложения | P03/P04; проверка условий R6 |
| P15 | Повторы WhatsApp без слепого дублирования | Часть F10 | Backend + Node | P05; договорённость о жизненном цикле операции |
| P16 | Владение данными и проверяемые границы модулей | F06 | Backend/архитектура | Анализ сразу; правила после P00 |
| P17 | Бизнес-сценарии вынесены из HTTP-контроллеров | F19 | Backend | P16; текущие регрессионные тесты |
| P18 | Декомпозиция финансовых и управляющих сервисов | Backend-часть F07, F06 | Backend | P16; P17 по затрагиваемому сценарию |
| P19 | Feature API и независимое состояние экранов | Клиентская часть F07 | Web/mobile | P01/P02; контракты P20 по мере переноса |
| P20 | Общий источник API-контрактов и чистых функций | F15 | Backend + web/mobile | P00; согласованная сборка пакетов |
| P21 | Сквозные проверки и закрытие замечаний по evidence | F16, итог всех пакетов | Разработка + QA + эксплуатация | Добавляется с первых исправлений, завершает каждый выпуск |

Наличие зависимостей не означает, что надо ждать завершения всего соседнего пакета. Например, проектирование JWT-протокола идёт параллельно резервированию; его включение в production ждёт успешного восстановления. Исправления UI можно выпустить до большой декомпозиции финансового ядра.

**P00. Зафиксировать исходную точку и сделать полный набор проверок доступным**

Работа:

1. Выбрать рабочий срез для реализации: commit, состав необходимых незакоммиченных изменений и версии конфигурации. Повторно проверить воспроизведение замечаний на этом срезе: проект менялся во время аудита.
2. Подготовить Java 26 и рабочий Docker engine для MySQL Testcontainers; использовать Maven wrapper проекта. Не обходить Enforcer отключением требования Java и не заменять MySQL на H2 ради зелёного результата.
3. Выровнять Node/npm с закреплённым CI/build runtime. Проверить чистую установку по существующим lockfiles в изолированной рабочей копии/CI. Обновление runtime делать отдельным изменением, только если оно необходимо.
4. Выполнить полный `mvnw verify`, клиентские tests/build, Node tests, инфраструктурные контракты; сохранить результаты. Каждую существующую ошибку отделить от ошибок будущего исправления.
5. Без вывода секретов определить, какие контуры реально используются: performers, outbound lead sync, outreach bridge, external worker, auth epoch, общий outbox. Уточнить число backend-инстансов и расположение schedulers.
6. Зафиксировать поддерживаемые mobile-релизы, публичные API/capability-сценарии и перечень таблиц/очередей, которые затронут изменения.

Опора: [quality-gates.yml](E:/Works/Projects/otziv/.github/workflows/quality-gates.yml), [pom.xml](E:/Works/Projects/otziv/backend/pom.xml), [R0_ROLLOUT_GUARDRAILS.md](E:/Works/Projects/otziv/docs/R0_ROLLOUT_GUARDRAILS.md).

Приёмка: есть воспроизводимая команда полного backend suite и отчёты на выбранном срезе; известны включённые функции и версии потребителей. Если найдено дополнительное падение, оно получает отдельную задачу и владельца; отключение теста не считается исправлением.

**P01. Исправить принадлежность мобильного редактора сущности**

Два небольших PR: основа runtime-тестов; исправление редакторов с regression cases.

Работа:

1. По образцу web добавить Angular unit-test target для mobile, отдельную команду `test:runtime`, test TS config и шаг CI. Сохранить текущие helper/source-contract tests.
2. Использовать реальные сервисы/компоненты через TestBed и DI, управляемый HTTP и fake timers. Capacitor-зависимости заменить тестовыми providers. Извлечение отдельных методов из AST, использованное для аудита, не делать постоянной тестовой архитектурой.
3. Сессия редактора хранит неизменяемый ID сущности и generation. Открытие другой сущности, закрытие, уход с экрана и завершение сессии инвалидируют предыдущие ответы.
4. Перед применением payload, ошибки и изменения loading проверять generation и ожидаемый ID. Защита должна покрывать заказ, компанию, платежные настройки компании, создание заказа и зависимые подкатегории.
5. Устаревшие GET отменять и дополнительно проверять generation. Сохранение формировать для ID текущей сессии; проверить совпадение с редактируемым payload.
6. Для POST/PUT/DELETE закрытие окна не объявляет серверную операцию отменённой. Сохранять captured entity ID; поздний ответ не меняет чужой экран. Если результат неизвестен, при возврате читать фактическое состояние, не повторять мутацию автоматически.

Файлы: [manager.page.ts](E:/Works/Projects/otziv/mobile/src/app/features/manager.page.ts), [route-epoch.guard.ts](E:/Works/Projects/otziv/mobile/src/app/core/route-epoch.guard.ts), [mobile/angular.json](E:/Works/Projects/otziv/mobile/angular.json).

Проверки: A→закрыть→B с ответами B→A; A→B→A; ошибка старого запроса; закрытие до ответа; двойная смена категории; Ionic уход/возврат; завершение записи после ухода.

Приёмка: тест с прежним кодом обнаруживает дефект; исправленный код сохраняет B независимо от порядка ответов. Данные, ошибка и loading от A не затрагивают B. Выпуск через native-процедуру P21; изменения API/БД не нужны.

**P02. Исправить контракт мобильной авторизации**

Работа:

1. Ввести явный результат refresh: пригодный access token; временная недоступность обновления; окончательно недействительная сессия. Конкретные enum/type names выбрать в PR.
2. Согласованно изменить `init`, `ensureAuthenticated`, `getAccessToken`, interceptor, guards, таймер и resume. Одна замена `return true` на `false` недостаточна: существующие callers могут стереть сохранённую сессию при обычном сетевом сбое.
3. При timeout/сети/временном серверном отказе сохранять refresh token и SecureStorage; expired access token не выдавать как пригодный. Показывать восстановимое состояние сети и предсказуемый повтор.
4. Сохранить один shared refresh promise и generation-защиту: одновременные API calls не создают несколько refresh, поздний refresh после logout не возвращает пользователя в сессию.
5. Разделить authenticated screen state и возможность выполнять серверную операцию. Ограничить retry после 401; повтор финансовой мутации допускается только при доказанной безопасности её повтора и сохранённом operation key.
6. Сохранить независимость public payment/review capability ссылок от login. Optional-auth сценарий должен работать при недействительной пользовательской сессии согласно существующему контракту.

Файлы: [auth.service.ts](E:/Works/Projects/otziv/mobile/src/app/core/auth.service.ts), [auth.interceptor.ts](E:/Works/Projects/otziv/mobile/src/app/core/auth.interceptor.ts), [role.guard.ts](E:/Works/Projects/otziv/mobile/src/app/core/role.guard.ts).

Проверки: fresh/expired token × success/timeout/5xx/terminal rejection; offline startup; concurrent requests; logout во время refresh; background/resume; повторный 401; публичная ссылка.

Приёмка: expired token нигде не возвращается как пригодный; временный отказ не удаляет сохранённую сессию; retry ограничен; нет восстановления сессии после logout. В production наблюдать неудачные login/refresh и повторные 401, без записи токенов в логи.

**P03. Исправить сериализацию команды VPS-синхронизации**

Работа:

1. Выделить явный codec для `LeadDtoTransfer`, согласованный с форматом принимающего приложения и текущим token-signing contract.
2. Учитывать, что проблемный сервис использует Jackson 2 `com.fasterxml.jackson.*`, а Spring Boot 4 поддерживает другой основной mapper. Нельзя предполагать, что любой auto-configured mapper подходит по типу и формату. Для локального исправления допустим явно настроенный Jackson 2 mapper с JavaTimeModule; глобальную миграцию JSON не объединять с исправлением retry. [Spring Boot JSON](https://docs.spring.io/spring-boot/reference/features/json.html).
3. Проверять обязательные поля и сериализацию до сохранения команды. Ошибка codec должна иметь явный исход; `{}` не является успешной сериализацией.
4. Сохранять версию payload и неизменяемые данные команды. Если интеграция передаёт последнее состояние вместо исторического события, явно зафиксировать эту семантику вместе с версией сущности/порядком обновлений.
5. Убедиться, что первичная отправка и повтор используют одинаковое бизнес-содержимое. Изменяемые транспортные атрибуты вроде времени подписи отделить от содержимого команды.

Файлы: [VpsSyncService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/l_lead/service/VpsSyncService.java), [LeadDtoTransfer.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/l_lead/dto/LeadDtoTransfer.java), [LeadSyncQueue.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/l_lead/model/LeadSyncQueue.java).

Проверки: round-trip DTO с LocalDate/LocalDateTime, null optional fields, кириллицей; forced transport failure; чтение известных старых корректных payload; несовместимая версия/повреждённый JSON.

Приёмка: после неудачной отправки сохранена полная пригодная для повтора команда; ошибки codec не маскируются. Существующие повреждённые записи обрабатываются отдельно в P04.

**P04. Исправить выборку retry и разобрать накопленные записи**

Работа:

1. Быстрым изменением вынести фильтр допустимых попыток в repository-запрос. Batch не должен состоять из записей, которые сервис затем безусловно пропускает.
2. Определить состояния runnable/processing/terminal/quarantined, due time, attempts и причину остановки. Названия и необходимость новых колонок уточнить по реализации; добавлять их новой Flyway-миграцией.
3. Построить bounded выборку с подходящим индексом. Для нескольких потребителей — атомарный claim и fenced completion; сетевое ожидание вне claim-транзакции.
4. Добавить backoff, классификацию временных/окончательных ошибок, верхнюю границу попыток и контролируемый replay. Повтор terminal-записи должен иметь понятный аудит.
5. Сначала выполнить read-only dry run существующей очереди: корректные, пустые, `{}`, повреждённые, неизвестной версии и исчерпанные записи. В отчёте — количества/ID с ограниченным доступом, без выгрузки PII.
6. Нельзя достоверно восстановить историческую команду из текущего состояния лида, если исходный payload потерян. Восстановление допускается только из авторитетных данных/аудита либо как новая операция передачи актуального состояния, если это соответствует контракту. Остальные записи карантинировать с причиной; не удалять молча.
7. Выполнить backfill маленькими повторяемыми пачками с checkpoint, dry run, контролем числа изменений и возможностью остановки.

Файлы: [LeadSyncQueueRepository.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/l_lead/repository/LeadSyncQueueRepository.java), [VpsSyncService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/l_lead/service/VpsSyncService.java), новые миграции в [db/migration](E:/Works/Projects/otziv/backend/src/main/resources/db/migration).

Проверки: N исчерпанных старых записей + новая runnable; два потребителя; истёкший claim; позднее завершение старого worker; poison payload; interrupted backfill; replay.

Приёмка: очередь продвигается, непригодные записи не отправляются, по каждой старой повреждённой записи известен исход. При переходе на новую семантику drain старых consumers обязателен: старый бинарник может игнорировать новые статусы. Откат на такой бинарник при непустой новой очереди запрещён технической проверкой совместимости.

**P05. Привязать лимит параллелизма к реальной работе**

Работа:

1. Отделить HTTP admission от владения permit выполняемой операции. Владельцем становится контекст задачи, освобождающий permit после завершения работы и cleanup.
2. `response.close`, `finish` и таймер не уменьшают счётчик живых задач автоматически. Они сигнализируют отмену там, где отмена действительно поддерживается.
3. Для Chromium/OCR/proxy дождаться закрытия ресурсов; повторный cleanup/release сделать идемпотентным. Лимит относится ко всей ресурсозатратной фазе.
4. Определить bounded обработку зависшей отмены: прекратить admission, учесть неизвестный исход, при необходимости завершить изолированный процесс. Нельзя вернуть permit, оставив неуправляемую тяжёлую работу жить.
5. Для WhatsApp не считать disconnect доказательством неотправленного сообщения. Зафиксировать неоднозначный результат для последующей обработки P15.
6. Добавить graceful drain, low-cardinality метрики running/cleanup/timeout/rejected и проверку startup limits.

Файлы: [worker/internal-auth.js](E:/Works/Projects/otziv/backend/external-review-worker/src/internal-auth.js), [worker/index.js](E:/Works/Projects/otziv/backend/external-review-worker/src/index.js), [whatsapp/internal-auth.js](E:/Works/Projects/otziv/whatsapp/internal-auth.js), [whatsapp/index.js](E:/Works/Projects/otziv/whatsapp/index.js).

Проверки: configured limit 1; disconnect с продолжающейся задачей; ответ до cleanup; timeout; исключение; двойной close; зависшая очистка; shutdown с активной операцией.

Приёмка: число фактически живых задач не превышает limit; нет преждевременного или двойного release. Worker обновляется после остановки новых claims и учёта текущих; gateway заменяется последовательно без параллельного запуска второй рабочей WhatsApp-сессии.

**P06. Устранить замечания по зависимостям**

Работа:

1. Повторить полный и production-only audit на актуальных lockfiles; сохранить advisory IDs, dependency paths, runtime/install reachability и доступные исправления. Результат аудита от 07.09 — исходная точка, а не неизменяемый список версий.
2. Подбирать минимальные совместимые обновления отдельно по приложениям. Начать с production-путей WhatsApp/worker, затем устранить dev/build findings клиентов.
3. Обновить manifests/lockfiles обычным разрешением версий. Проверить overrides и объяснить, какие нужны после обновления; не выполнять необдуманный `audit fix --force`.
4. Из чистой рабочей копии выполнить установку, tests/build и container smoke P10. Проверить совместимость whatsapp-web.js, Puppeteer и фактически используемого Chromium.
5. Сохранить текущие blocking thresholds CI. При отсутствии доступного исправления замечание остаётся открытым до документированного решения по риску с владельцем и сроком; тихое ослабление scanner не является результатом этого пакета.
6. Проверить Maven dependency scan и container image scan после восстановления окружения P00; новые результаты оформить отдельно от исходных npm findings.

Опора: [dependency-audit.yml](E:/Works/Projects/otziv/.github/workflows/dependency-audit.yml), [R10_CI_AND_REPOSITORY_CLEANUP.md](E:/Works/Projects/otziv/docs/R10_CI_AND_REPOSITORY_CLEANUP.md).

Приёмка: существующие blocking audits проходят для подготовленного релиза, приложения собираются из lockfiles, runtime smoke успешен. Откат — предыдущий совместимый immutable image; возвращённые уязвимые зависимости снова считаются открытым риском.

**P07. Сделать восстановление всей системы проверяемым**

Три PR: карта восстановления; PostgreSQL pipeline и manifest; комплексный drill.

Работа:

1. Инвентаризировать MySQL, PostgreSQL Keycloak, значимые S3-объекты/версии, ключи шифрования, секреты, signing material и состояние интеграций. Уточнить существующие внешние backup, чтобы использовать уже работающий механизм, если он есть.
2. Для каждого элемента определить владельца, источник восстановления, независимость хранилища, retention и требуемые RPO/RTO. Значения согласовать с бизнесом до объявления системы восстановимой.
3. Добавить PostgreSQL backup с шифрованием, проверкой удалённого объекта и защищёнными credentials. Realm JSON не заменяет полный backup базы: у Keycloak export/import есть ограничения как у механизма восстановления. [Keycloak import/export](https://www.keycloak.org/server/importExport).
4. Создать manifest комплекта: версии приложения/Keycloak/схем, момент и способ получения снимков, hash/object version, необходимые ключи по безопасным идентификаторам. Секретные значения в manifest не включать.
5. Доказать согласованность восстановления MySQL↔Keycloak: произвольные дампы разных моментов могут нарушить связь `users.keycloak_id` с subject. Выбрать согласованный write fence/maintenance для связанных изменений либо иной проверяемый recovery protocol.
6. Сохранить существующий изолированный MySQL drill и добавить отдельный комплексный стенд: закрытая тестовая сеть, восстановленные БД и приложение, отключённые реальные отправки, платежи, webhooks и outbound schedulers.
7. Восстановить из manifest, проверить IDs/роли, login/refresh тестовой учётной записи, доступ к зависимым объектам и расшифровку необходимых сохранённых данных. Измерить recovery point и время восстановления.
8. Проверить отказ при повреждённом объекте, чужом ключе, недостающем компоненте и несовместимой версии. Документировать действия при частичной потере компонентов.
9. Перед возвратом восстановленной системы в публичный доступ сверить или инвалидировать восстановленные сессии и поколения security state. Возврат старого snapshot не должен оживлять ранее отозванный доступ; выбранная процедура может потребовать повторного входа и проверки token-signing/revocation состояния.

Опора: [DatabaseBackupService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/s3/backup/service/DatabaseBackupService.java), [R0_BACKUP_RESTORE_DRILL.md](E:/Works/Projects/otziv/docs/R0_BACKUP_RESTORE_DRILL.md), [DISASTER_RECOVERY_SOURCE.md](E:/Works/Projects/otziv/docs/DISASTER_RECOVERY_SOURCE.md), [docker-compose.yaml](E:/Works/Projects/otziv/docker-compose.yaml).

Приёмка: другая изолированная среда восстанавливает работоспособную систему из независимого комплекта; измеренные RTO/RPO удовлетворяют согласованным требованиям. Простое наличие файла backup или зелёная проверка `.env.prod.example` не закрывает F04. Новый pipeline вводится рядом с действующим MySQL backup; его откат сохраняет существующие копии и ключи.

**P08. Сузить доступ Dozzle/Alloy к Docker API**

Работа:

1. Зафиксировать реально нужные API: discovery, inspect в необходимом объёме, logs/events. Учитывать, что даже read API могут раскрывать env/конфигурацию, поэтому разрешать минимально необходимое.
2. Выбрать ограниченный proxy с deny-by-default либо сбор логов без socket. Прямой socket не передавать consumers; если используется proxy, он остаётся единственным минимальным доверенным компонентом с таким доступом.
3. Изолировать proxy сетью, не публиковать наружу; закрепить методы/пути и их ограничения. Не включать широкую категорию API только ради одного endpoint.
4. На одноразовом Docker-стенде проверить нужный discovery/streaming и запрет create/start/exec/delete/build, неизвестных endpoints и обходов правил.
5. Обновить compose, Alloy config, инфраструктурные контракты и runbook; выпускать consumers по одному с проверкой потока логов.

Опора: [docker-compose.yaml](E:/Works/Projects/otziv/docker-compose.yaml), [config.alloy](E:/Works/Projects/otziv/infrastructure/alloy/config.alloy).

Приёмка: consumers продолжают выполнять нужные операции, но не управляют контейнерами/host через daemon. При неисправности допустимо временно отключить viewer/collector и использовать доступные журналы; возвращение прямого socket означает возвращение риска и не считается безопасным завершением rollback.

**P09. Настроить внешний контроль и реакцию на отказы**

Работа:

1. Определить существующий или новый внешний monitoring endpoint, ответственного и канал доставки. Выбор поставщика, тарифа и расписания дежурств — отдельные решения, которых аудит не устанавливал.
2. Описать правила и contact points в воспроизводимой конфигурации; секреты вынести во внешнее защищённое хранилище.
3. Добавить внешний availability probe, отсутствие scrape/heartbeat, ошибки/latency/saturation, возраст последнего verified backup по каждой БД, backlog/oldest due/DEAD/UNKNOWN, зависший cleanup worker.
4. Привязать пороги к согласованным SLO/RPO, а не к произвольным числам. Отличать существующий recoverable backup object от сбоя его последующей доставки/cleanup.
5. Настроить дедупликацию, resolve, maintenance/silence с ограниченным сроком и runbook каждого actionable alert. Не использовать payload, телефоны или entity IDs как массовые metric labels.
6. Провести управляемые drills: недоступен backend, весь Docker-host, backup destination и сам канал уведомления. Проверить реальную доставку тестового сигнала ответственному в рамках согласованной процедуры.

Опора: [prometheus.yml](E:/Works/Projects/otziv/infrastructure/prometheus/prometheus.yml), [BackupScheduler.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/s3/backup/service/BackupScheduler.java), [infrastructure/grafana](E:/Works/Projects/otziv/infrastructure/grafana).

Приёмка: отказ всего приложения не отключает его внешний контроль; просроченная backup обнаруживается в требуемый срок. Наличие красивого dashboard не является достаточным результатом. При шуме корректировать отдельное правило, сохраняя доступность и backup-age alerts.

**P10. Проверять готовность Chromium/OCR в собранном образе**

Работа:

1. Сохранить лёгкий `/health` для живого процесса; `/ready` связать с завершённым startup self-check и возможностью принять работу.
2. Один раз с ограниченным временем проверить запуск/закрытие Chromium и OCR на локальной fixture. Не открывать внешние сайты и не создавать тяжёлые процессы на каждый probe.
3. Проверить наличие и доступность моделей OCR и необходимых writable путей при read-only filesystem. По возможности включить runtime assets в воспроизводимый образ; загрузка при старте должна иметь явную readiness-семантику.
4. Подключить имеющийся Chromium smoke и OCR-проверку в CI именно для собранного production image: non-root, sandbox, resource bounds и сетевые ограничения остаются включёнными.
5. Установить корректную связь readiness с маршрутизацией/claim-потреблением. В текущем Compose само слово ready не гарантирует, что вызывающий backend перестал отправлять задания.

Опора: [worker/index.js](E:/Works/Projects/otziv/backend/external-review-worker/src/index.js), [chromium-smoke.js](E:/Works/Projects/otziv/backend/external-review-worker/src/chromium-smoke.js), [worker/Dockerfile](E:/Works/Projects/otziv/backend/external-review-worker/Dockerfile).

Приёмка: отсутствующий бинарник/модель/sandbox делает worker not ready; исправный image обрабатывает fixture и освобождает ресурсы. Внешние captcha/429 и backlog не создают бесконечную цепь рестартов. После P05/P06 повторяется проверка итогового совместного image.

**P11. Завершить протокол отзыва сессий и JWT**

Это серия PR с отдельным проектным решением и интеграционным стендом Keycloak.

Работа:

1. Обновить документацию по фактическому коду. Сейчас `LocalJwtSecurityStateFilter` уже отклоняет присутствующий несовпадающий epoch; false у `auth-epoch-claim-required` разрешает только отсутствие claim. Добавление mapper может изменить поведение немедленно, даже при false.
2. Описать authoritative security state, границу завершённой security mutation и все пути изменений: backend, Keycloak account console, reset credentials/admin, роли/активность, миграции. Покрыть частичный сбой между MySQL и Keycloak.
3. Связать epoch с поколением аутентифицированной сессии. Mapper, копирующий текущий user attribute при каждом refresh, может выдать старой сессии новый epoch; такой механизм недостаточен.
4. Выбрать и доказать протокол login/refresh против reset/logout: сериализация или fencing, обработка незавершённой security mutation, повтор и reconciliation. Новый подписанный токен от устаревшей сессии не должен становиться пригодным после отзыва.
5. Подготовить совместимые issuer/backend изменения и bootstrap существующих пользователей. Shadow должен быть настоящим наблюдением: не публиковать незавершённый claim в поле, которое старый backend уже принудительно проверяет. Выбранная переходная схема должна работать со всеми одновременно обслуживающими версиями.
6. Не ослаблять действующие active/sub/roles и проверки заведомого mismatch ради shadow. Service-account exemptions перечислить явно и проверить отдельно; пользовательский токен не должен получать exemption случайно.
7. После согласованного backfill и наблюдения включить обязательность claim для проверенного охвата. Переход с нескольких режимов требует автоматических checks совместимости конфигурации, а не ручной памяти оператора.
8. Проверить old mobile/new backend, web, public optional auth, `/api/me`, push revoke и поведение при offline refresh. Продолжительность совместимого окна определяется действующими сессиями и политикой выпуска, а не выдуманным количеством часов.

Опора: [LocalJwtSecurityStateFilter.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/u_users/config/LocalJwtSecurityStateFilter.java), [UserAuthEpochService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/u_users/service/UserAuthEpochService.java), [KeycloakUserProvisioningService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/u_users/service/KeycloakUserProvisioningService.java), [AUTH_EPOCH_PUSH_REVOKE_ROLLOUT.md](E:/Works/Projects/otziv/docs/AUTH_EPOCH_PUSH_REVOKE_ROLLOUT.md).

Проверки: concurrent reset/login/refresh; двойная смена пароля/роли; crash между шагами; отсутствие Keycloak/БД; pending mutation; missing/malformed/mismatched claim; subject mismatch; service principals.

Приёмка: после подтверждённого отзыва старый JWT не принимается, а старая сессия не получает пригодный JWT нового поколения; новый законный вход работает. До production rollout выполнен P07. При откате сохраняются epoch и durable state; они не уменьшаются и старые сессии не оживляются. Если временно возвращается совместимость missing-claim, прежнее окно риска считается снова открытым.

**P12. Сделать переходы предложений исполнителям атомарными**

Работа:

1. Описать единую state machine assignment/offer и список законных переходов, включая offer, accept, decline, expire, walk, publish, verify и последствия для счётчиков.
2. Определить общий lock order с учётом уже существующих order/review/user/performer операций. Не назначать порядок блокировок локально в одном методе без проверки соседних путей.
3. Реализовать переход по ожидаемому состоянию: единая блокировка агрегата с повторной проверкой либо CAS/optimistic version с проверкой результата и обработкой конфликта. Все входы — API, Telegram, scheduler — используют один механизм.
4. Зафиксировать инвариант единственного активного предложения на уровне БД, если бизнес-процесс действительно допускает только одно. Выбрать применимую для текущего MySQL схему ограничения/указателя; перед созданием ограничения выполнить dry run конфликтующих данных.
5. Обеспечить корректность инкрементов статистики и терминальных переходов при повторах. Для фоновой выборки добавить claim или доказанный атомарный механизм исключения двойного предложения.
6. Отдельно разобрать существующие конфликтные offers без массового автоматического наказания/изменения исполнителя.

Файлы: [PerformerAssignmentService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/performers/service/PerformerAssignmentService.java), [ReviewPerformerOfferRepository.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/performers/repository/ReviewPerformerOfferRepository.java), [ReviewPerformerAssignment.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/performers/model/ReviewPerformerAssignment.java).

Проверки на MySQL: accept/expire на границе времени, double accept, accept/decline, два scheduler, stale transaction, rollback и повтор команды. Управлять interleaving барьерами, не случайным sleep.

Приёмка: единственное согласованное решение, корректные counters, понятный конфликт клиенту. При смене concurrency semantics старые workers drain/останавливаются перед переключением; смешанный fleet, где часть кода обходит locks, не считается защищённым.

**P13. Исправить доставку предложений и уведомлений исполнителям**

Серия PR после P12: readiness notification; offer delivery/TTL; recovery старых записей.

Работа:

1. Создавать durable намерение уведомления в транзакции изменения задания; сетевую отправку выполнять после завершения транзакции отдельным dispatcher.
2. Для «можно публиковать» использовать уникальный бизнес-ключ задания и типа/поколения уведомления, claim, nextAttemptAt и явный delivery outcome. Повторное напоминание, если нужно бизнесу, получает отдельный интервал и идентичность.
3. Выборка обрабатывает только due runnable intents, поэтому первая страница не блокирует остальные. Ошибка доставки не изображается успешной отправкой.
4. Предложение и его доставка имеют разные состояния. Определить, с какого подтверждённого события начинается TTL; недоставленное предложение не должно автоматически увеличивать вину/штрафную статистику исполнителя.
5. Telegram не даёт универсальной гарантии exactly-once. При timeout после возможной отправки фиксировать UNKNOWN и reconciliation/контролируемое решение. Простой marker после отправки оставляет окно crash между send и marker.
6. Для уже ожидающих публикации заданий выбрать rollout-политику: часть могла быть ранее уведомлена без marker. Не объявлять их все неотправленными автоматически; сначала dry run и ограниченный по объёму управляемый backfill/напоминание.
7. Для старых OFFERING без подтверждённой доставки определить отдельную сверку и правила TTL. Зафиксировать невозможность точно восстановить отсутствовавшую историю.

Опора: [PerformerAssignmentScheduler.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/performers/service/PerformerAssignmentScheduler.java), [PerformerTelegramNotificationService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/performers/service/PerformerTelegramNotificationService.java), существующие domain delivery/claim-паттерны в платежных подсистемах.

Проверки: два tick и две реплики; ошибка БД до commit; внешний send success + crash до записи результата; definite failure; UNKNOWN; повтор подтверждения; больше batch заданий; delayed callback.

Приёмка: обычные tick не спамят; очередь продвигается; сообщения не отправляются до commit; TTL/counters соответствуют выбранной политике доставки. Общий R6 relay для Telegram не включается автоматически: сначала должен быть доказан подходящий контракт ambiguous delivery, либо используется отдельная domain state machine.

**P14. Сделать исходящую синхронизацию лидов восстанавливаемой**

Работа:

1. Уточнить нужность и фактическое использование `lead.sync.outbound.enabled` и outreach bridge. Выключенный контур исправлять до включения; если он больше не нужен, его вывод из эксплуатации оформляется с проверкой потребителей и сохранённых очередей.
2. Описать контракт события/последнего состояния, operation ID, версию entity, порядок применения, idempotency и подтверждение получателя. Договориться с владельцем принимающего приложения о необходимой поддержке.
3. Записывать intent вместе с бизнес-изменением. Удалить роль log-only трёх попыток как единственного механизма доставки; HTTP выполняется отдельным relay вне пользовательской транзакции/response path. Не передавать managed JPA entity в async-поток до commit: использовать сохранённую команду либо её ID с чтением в собственной согласованной транзакции.
4. Использовать общий outbox только после проверки условий R6, приведённых ниже. Конкретный producer/handler получает отдельный rollout scope, deadline и проверяемый replay.
5. Проверить out-of-order и повтор старого изменения после нового. Если передаётся последнее состояние, получатель не должен откатиться на устаревшую версию.
6. Сначала записывать наблюдаемую теневую команду без второй отправки либо использовать единый бизнес-ключ обеих реализаций при доказанной дедупликации. Две независимые реальные отправки не являются безопасным shadow.
7. При переключении иметь ровно одного владельца отправки; учитывать in-flight, неполученные подтверждения и очередь от прежней реализации.

Файлы: [LeadUpdateEventListener.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/l_lead/event/LeadUpdateEventListener.java), [LeadTransferServiceImpl.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/l_lead/service/LeadTransferServiceImpl.java), [OutreachBridgeService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/outreach_bridge/OutreachBridgeService.java).

Приёмка: rollback бизнес-транзакции не создаёт внешнего изменения; crash после commit сохраняет задачу; retry не создаёт повторного бизнес-эффекта; новый event type не забирается старым обработчиком. Откат останавливает producer/relay согласованно, сохраняя intents; возврат прямой отправки при неизвестных in-flight исходах требует reconciliation.

**P15. Добавить сквозную идентичность исходящей WhatsApp-операции**

Работа:

1. Создавать стабильный business operation ID в backend до отправки. Хранить его в durable задании; одинаковый повтор использует прежний ID и тот же payload.
2. Gateway ведёт durable registry: operation ID, проверяемый envelope с client/gateway scope, получателем, типом операции и hash содержимого, статус, claim и сохранённый результат/message ID. Один ID с другим адресатом, scope или содержимым отклоняется. Registry переживает restart.
3. Разделить not started, running, confirmed success, definite failure и UNKNOWN. HTTP timeout/disconnect и crash после возможной отправки не означают definite failure.
4. Повтор confirmed success возвращает известный результат без нового `sendMessage`; параллельные повторы не стартуют две операции. Retention registry покрывает весь согласованный retry/replay horizon, иначе дедупликация исчезнет раньше старых повторов.
5. Описать reconciliation UNKNOWN по доступной внешней истории/message ID или ручному решению. Если достоверное сопоставление невозможно, это явно показывается оператору; автоматический повтор не включается под видом гарантии exactly-once.
6. Связать P05 limiter с той же операцией. Отдельно обработать shutdown/gateway session replacement и сохранение registry volume.
7. Выпустить совместимое поле/endpoint и backend producer поэтапно. Legacy запросы без ключа не получают новую гарантию; постепенно измерить и убрать их использование.

Файлы: [WhatsAppServiceImpl.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/whatsapp/service/WhatsAppServiceImpl.java), [ScheduledClientMessageService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/client_messages/service/ScheduledClientMessageService.java), [whatsapp/index.js](E:/Works/Projects/otziv/whatsapp/index.js).

Проверки: одинаковый ID последовательно/параллельно; ID с другим payload; потерянный HTTP-ответ после success; crash до/после `sendMessage`; повтор после restart; expiry registry; reconciliation UNKNOWN.

Приёмка: подтверждённая операция не отправляется повторно; неопределённый исход видим и не перезапускается слепо. Реальные сообщения не используются как автоматические CI fixtures. Rollback сохраняет registry и останавливает несовместимые retries.

**Условия подключения к общему outbox R6 — для P11/P14/P15, где он действительно применим**

В проекте foundation существует, но её нельзя считать готовой заменой всех интеграций. Для каждого подключаемого event type отдельно нужны:

- transport deadlines короче lease и ограниченный executor; освобождение claim не означает отмену внешнего вызова;
- единая бизнес-идентичность legacy и нового пути, дедупликация у получателя и понятная семантика UNKNOWN;
- producer ordering/versioning агрегата; отсутствие обгона скрытой незакоммиченной команды;
- replay DEAD с прежним event ID, retention/tombstones и ограниченный доступ оператора;
- mixed-version tests и наличие подходящего handler до включения producer;
- метрики, наблюдаемый backlog и процедура остановки/возобновления;
- проверка payload policy: данные минимальны, секреты разрешаются при отправке и не переносятся в event.

Если для внешнего API эти условия недостижимы, нужно проектировать domain delivery state/reconciliation с честной гарантией, а не включать автоматические повторы общего relay. Основание: [R6_TRANSACTIONAL_OUTBOX.md](E:/Works/Projects/otziv/docs/R6_TRANSACTIONAL_OUTBOX.md).

**P16. Зафиксировать владение данными и проверять границы модулей**

Работа:

1. Описать фактические context/container схемы и предметные области. 54 top-level Java-пакета не следует автоматически объявлять 54 независимыми бизнес-модулями.
2. Для таблиц и критических инвариантов назначить владельца: кто создаёт/меняет заказ, счёт, платёжную попытку, начисление, пользователя, предложение и внешний intent.
3. Для каждого сценария определить application entry point, public module API, разрешённые зависимости и порядок транзакций. Cross-domain reporting может иметь отдельную явно ограниченную read-модель.
4. Зафиксировать решения в нескольких коротких ADR: авторизация, деньги/счета, delivery, shared client contracts, deployment/recovery. Добавить объяснение связи legacy MVC и REST.
5. Выбрать проверку архитектуры, допускающую постепенное внедрение: ArchUnit, Spring Modulith или эквивалент. Сначала проверить совместимость с текущим стеком и небольшой реальный контур.
6. Зафиксировать baseline существующих нарушений с владельцами; запретить новые обращения к внутренним репозиториям/классам и новые циклы между выбранными бизнес-модулями. Удалённое нарушение не должно автоматически возвращаться в baseline.
7. Оставить маленький shared kernel только для действительно общих примитивов; перенос спорного кода в бесконечный `common` не устраняет связанность.

Приёмка: CI падает на намеренно добавленной запрещённой зависимости, разрешённые вызовы работают; reviewers знают, какому модулю принадлежит изменяемое правило. Метрика SCC полезна для динамики, но не является самостоятельной целью «обнулить все импорты».

**P17. Вынести бизнес-операции из контроллеров**

Работа:

1. Начать с изменения статуса заказа в `ApiWorkerBoardController`: зафиксировать текущее поведение, permissions, counters, waiting state, publication dates и audit.
2. Выделить application command с actor/context и полным атомарным сценарием. Транзакция принадлежит application service; внешние эффекты следуют установленному delivery contract.
3. Controller оставляет разбор и валидацию запроса, преобразование principal, вызов сценария и HTTP mapping. Проверки бизнес-доступа должны оставаться действующими и при вызове сценария из другого транспорта.
4. Перевести связанные API/Telegram/scheduler входы на один use case там, где бизнес-действие действительно одно. UI-специфические формы не обязаны быть одинаковыми.
5. Следующими PR переносить изменение компании/аккаунта и другие мутации controller; не переносить всё содержимое класса в один новый сервис с тем же набором обязанностей.

Опора: [ApiWorkerBoardController.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/p_products/controller/ApiWorkerBoardController.java).

Проверки: одинаковые инварианты для разрешённых входов, отказ для чужого объекта/роли, rollback полной команды, HTTP status/error contract, audit once.

Приёмка: HTTP-слой не владеет рассматриваемыми бизнес-переходами; новый транспорт может вызвать тот же сценарий без копирования его правил. Рефакторинг сохраняет наружный контракт и не вводит лишние БД-запросы.

**P18. Разделить финансовые и управляющие сервисы по сценариям**

Первые кандидаты: [CommonBillingService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/common_billing/service/CommonBillingService.java), [PaymentLinkService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/payments/service/PaymentLinkService.java), [ManagerControlService.java](E:/Works/Projects/otziv/backend/src/main/java/com/hunt/otziv/manager_control/service/ManagerControlService.java).

Работа:

1. Составить каталог методов по завершённым сценариям и измерить исходные latency/DB calls для наиболее важных. Зафиксировать владение транзакцией и lock order до переноса.
2. Сначала выделять leaf-обязанности: query/assembling, форматирование сообщений, чистые политики. Затем — lifecycle счёта, создание/инициализация платёжной попытки, возврат/смена маршрута, reconciliation и delivery.
3. Сохранить один авторитетный orchestration entry point для каждой операции. `ObjectProvider<CommonBillingService> ↔ ObjectProvider<PaymentLinkService>` заменять явным направлением зависимостей и узкими API, а не новой парой циклических facades.
4. Инварианты денег и принадлежности платежа остаются в доменной/application-части. Банк — адаптер; бизнес-правила не должны зависеть от конкретного HTTP-контроллера банка.
5. Переносить транзакционную границу целиком. Проверять propagation, proxy/self-invocation и момент выполнения after-commit: извлечение метода в другой bean способно изменить семантику без изменения его тела.
6. Не заменять синхронную атомарную финансовую операцию событиями только ради удаления import. Там, где нужна атомарность одной MySQL-транзакции, её сохранять.
7. ManagerControl разделять по диагностике, query, решениям workflow и выполнению действий; разделять чтение отчёта и изменение бизнес-состояния.
8. Для каждого перенесённого сценария удалить старую дублирующую реализацию после подтверждения использования нового пути. Временный делегирующий facade имеет владельца и условие удаления.

Проверки: денежные суммы/округление, повтор команды/webhook, route change, partial/unknown bank result, возврат, concurrent mutation, interrupted reconciliation; соответствующие MySQL suites и сравнение query/latency до/после.

Приёмка: все перечисленные hotspots разобраны по ответственности; критические сценарии имеют обозримые отдельные границы и проверки зависимостей, двусторонняя сервисная связь устранена без потери атомарности. Число строк само по себе не закрывает F07. В каждом PR меняется один сценарий, отдельно от исправления его бизнес-поведения и массового переименования пакетов.

**P19. Разделить клиентские API и состояние крупных экранов**

Работа:

1. Из mobile `ApiService` по областям выделить clients: orders/manager, companies, common billing/payments, worker, справочники. Общий HTTP/auth/error transport сохранить; старый API временно делегирует перенесённые методы.
2. Из mobile manager первым выделить order editor facade с исправленной generation-защитой P01, затем company editor и billing editor.
3. Из mobile order-details выделять редактирование отзывов, подготовку публикации, payment flow и отчёт компании. Извлечение шаблона и изменение финансового поведения выпускать разными PR.
4. Web dictionaries разделить на независимые функции/вкладки: аккаунты/телефоны, payment settings, gamification, integrations, города/расстояния. Для остальных крупных страниц применить те же критерии владения состоянием по мере каталога hotspots.
5. Feature facades, владеющие редактируемым состоянием, ограничить жизнью страницы/редактора. Root singleton уместен для транспорта/авторизации, но способен смешать два редактора.
6. Учитывать Ionic cached pages: одного `ngOnDestroy` недостаточно для остановки видимых-page запросов/таймеров; обработать leave/enter и session invalidation.
7. Переносить состояние, запросы, ошибки и тесты одного сценария вместе; запретить новым features обращаться к старому общему API напрямую после миграции соответствующей области.

Опора: [mobile/api.service.ts](E:/Works/Projects/otziv/mobile/src/app/core/api.service.ts), [manager.page.ts](E:/Works/Projects/otziv/mobile/src/app/features/manager.page.ts), [order-details.page.ts](E:/Works/Projects/otziv/mobile/src/app/features/order-details.page.ts), [admin-dictionaries.component.ts](E:/Works/Projects/otziv/frontend/src/app/features/admin/dictionaries/admin-dictionaries.component.ts), существующие web manager/worker facades.

Приёмка каждой волны: routes/forms/payload/permissions сохранены; нет запросов скрытых вкладок и лишнего polling; состояние разных экранов не смешивается; P01/P02 runtime regressions проходят. Итоговое закрытие F07 требует разделения всех перечисленных hotspots, а не только первого удачного примера.

**P20. Создать единый источник API-контрактов и общих чистых функций**

Две согласованные ветки: контракт/SDK и независимые от UI правила.

Работа:

1. Начать с DTO common billing/payments, затем manager/orders. Описать фактический JSON-контракт backend, включая optional/null, даты, IDs, деньги, enum, ошибки, pagination и права.
2. Выбрать версионируемую OpenAPI-схему с проверкой против runtime JSON и воспроизводимую генерацию DTO/SDK. Версии генератора и пакета закрепить; generated files не редактировать вручную.
3. Сохранить Angular HTTP/interceptor, public capability и native transport semantics через адаптер; генерированный client не должен обходить их новым самостоятельным fetch-слоем.
4. Выбрать способ распространения общего пакета, работающий из clean checkout и всех Docker build contexts. Локальный symlink на соседнюю папку недостаточен: текущие приложения собираются отдельно. Не считать внешний package registry уже имеющимся.
5. Поддерживать fixtures последнего выпущенного и минимально поддерживаемого mobile. Сначала совместимое backend-расширение, затем клиент; удаление endpoint/полей — после завершения окна поддержки и измерения использования.
6. Не считать любой новый enum автоматически совместимым: старый клиент должен предсказуемо обработать неизвестный статус, особенно в платежах.
7. В маленький независимый пакет вынести побайтно одинаковые `manual-payment-recipient-summary` и `manual-payment-task-visibility`. Запретить зависимости от Angular, Capacitor, DOM, HTTP/storage. Отличающиеся routing helpers сначала сравнить таблицей поведения и сохранить обоснованные различия в адаптерах.
8. Сервер остаётся авторитетным для денег/получателя/прав; общая клиентская функция не заменяет серверную проверку. Изменение общего пакета запускает suites/build обоих клиентов.

Опора: [web/common-billing.api.ts](E:/Works/Projects/otziv/frontend/src/app/core/common-billing.api.ts), [mobile/api.service.ts](E:/Works/Projects/otziv/mobile/src/app/core/api.service.ts), [frontend/Dockerfile](E:/Works/Projects/otziv/frontend/Dockerfile), [web/shared](E:/Works/Projects/otziv/frontend/src/app/shared), [mobile/shared](E:/Works/Projects/otziv/mobile/src/app/shared).

Приёмка: повторная генерация не меняет рабочее дерево; несовместимый drift обнаруживается CI; поддерживаемый старый mobile работает с новым backend, а новый клиент — с предыдущим поддерживаемым backend, на который предусмотрен откат. Отсутствие optional fields/capabilities не запускает небезопасный финансовый fallback. Общий helper имеет один исходник и прежнее проверенное поведение. Новая версия SDK не требует одновременного обновления всех устройств.

**P21. Встроить сквозные проверки и правила выпуска с первого исправления**

Работа:

1. Regression test добавляется вместе с каждым исправлением и подтверждается на прежнем поведении. Для reversible low-impact переносов без новой логики использовать существующие содержательные проверки; не писать тест, который лишь повторяет структуру нового метода.
2. Добавить небольшой browser E2E-набор web/mobile-web: вход/роль, редактор и сохранение, public capability, платёжный сценарий с fake provider, timeout/retry. Управлять ответами сети детерминированно.
3. Сохранить MySQL Testcontainers, расширить доказательства конкурентности и restart/replay. Для миграций проверять пустую БД и upgrade с поддерживаемой предыдущей схемы/репрезентативных обезличенных данных.
4. Для native релизов проверить установку поверх поддерживаемого Android, SecureStorage после restart, PKCE/deep links, background/resume, сеть, logout/refresh и push revoke у push-варианта. iOS device/build gate нужен, если платформа реально выпускается.
5. Выполнить targeted performance checks затронутых запросов/очередей на репрезентативном объёме: EXPLAIN, число запросов, latency и saturation. Согласовать SLO; не заявлять выдерживаемое число пользователей по размеру исходников.
6. Проверить required CI checks и release lineage; не настраивать обязательным отсутствующий на части PR workflow так, чтобы PR навсегда ожидал несуществующий статус. Использовать действующий runbook CI.
7. Включить container smoke, drill восстановления, доступность внешнего alert route и rehearsed rollback в release-процедуру по типу изменения. Результаты сохранять с exact commit/image digest/schema/SDK/APK hash.
8. Для каждого F01–F20 хранить ссылку на PR, regression evidence, миграцию/backfill при наличии, результат rollout и владельца. Статусы: запланировано → реализовано → проверено → выпущено → наблюдение завершено/закрыто.

Опора: [quality-gates.yml](E:/Works/Projects/otziv/.github/workflows/quality-gates.yml), [ANDROID_RELEASE_SIGNING.md](E:/Works/Projects/otziv/docs/ANDROID_RELEASE_SIGNING.md), [MOBILE_SELF_UPDATE.md](E:/Works/Projects/otziv/docs/MOBILE_SELF_UPDATE.md), [PROD_DOCKER.md](E:/Works/Projects/otziv/PROD_DOCKER.md).

Native выпуск: сначала внутренние тестовые устройства, затем общая публикация. Процентный rollout нельзя обещать без реализации cohorts: текущий self-update ориентирован на latest release. APK неизменяем, подпись/package сохраняются, versionCode растёт; release artifacts заранее архивируются независимо от server latest storage. Флаги `required` и `minSupportedVersionCode` не повышаются автоматически вместе с публикацией: сначала подтверждаются upgrade и восстановительный выпуск, затем отдельным переключением завершается поддержка старой версии.

Native откат: остановить распространение дефектного релиза; уже обновлённым Android-устройствам выпустить проверенную прежнюю логику с новым большим versionCode и той же подписью. Не подменять APK под прежней версией и не требовать удаления приложения. Storage и backend сохраняют совместимость переходных версий.

Приёмка: замечание закрыто только после требуемого для него уровня проверки. Для F04 нужен реальный drill, для F13 — issuer/backend интеграция и выпуск, для F01 — установленный клиент, для F17 — фактическое внешнее оповещение. Зелёный unit suite не подменяет эти результаты.

**Общие правила работы со схемой и накопленными данными**

- Миграции append-only: новый номер выбирается при реализации из актуальной ветки. Старые SQL не исправляются задним числом.
- Изменения выпускать как расширение схемы → совместимый код → проверяемый backfill → переключение → удаление старого после завершения поддержки. Обратная совместимость должна проверяться, а не предполагаться по наличию nullable column.
- Backfill идемпотентен, выполняется ограниченными пачками, имеет dry run/checkpoint и счётчики. Для financial/identity данных предварительно определить авторитетный источник и смысл восстановления.
- Старые `{}`, неизвестные исходы внешних операций и отсутствующие delivery markers не превращаются в достоверную историю предположением. Невосстановимые случаи выделяются для controlled reconciliation.
- При изменении claim/lease/state semantics старые writers/consumers должны быть совместимы либо остановлены и выведены из обработки до cutover.
- Down-migration/restore всей БД не является обычным rollback кода: это может потерять новые записи и не отменит уже отправленное сообщение или банковскую операцию. Для внешнего эффекта требуется бизнес-компенсация или reconciliation.

**Постоянные продуктовые ограничения при исправлениях**

Сохраняются анонимные публичные review/payment capability-сценарии с разрешёнными действиями, матрица существующих ролей и права на конкретные объекты. Legacy MVC и mobile старых поддерживаемых версий учитываются в совместимости. Опора — [R0_ROLLOUT_GUARDRAILS.md](E:/Works/Projects/otziv/docs/R0_ROLLOUT_GUARDRAILS.md).

Введение более строгой авторизации, новой очереди или общего SDK не должно случайно превращать public-ссылку в требующую login, стирать native session при временной сети или запускать второй платёж/отправку. Эти случаи входят в acceptance tests затрагивающих изменений.

**Контрольные результаты программы**

| Результат | Что должно быть доказано |
|---|---|
| M0 — проверяемая исходная точка | P00 завершён; full Java suite доступен, активность функций и поддерживаемые версии известны |
| M1 — первые исправления выпущены | P01/P02/P03/P04/P05 по используемым контурам прошли regression и соответствующий rollout; состояние старых записей учтено |
| M2 — эксплуатационные риски сокращены | P06–P10: audits, ограниченный Docker access, полноценное восстановление, внешний alert и рабочий container smoke |
| M3 — критичные процессы имеют согласованные гарантии | P11–P15: отзыв сессии, атомарные performers, delivery/UNKNOWN/replay доказаны соответствующими тестами и выпуском |
| M4 — архитектура управляется автоматически | P16–P20: владельцы данных и контракты явны, hotspots разделены, новые нарушения границ блокирует CI |
| M5 — замечания закрыты по evidence | P21 и итоговая матрица не содержат неподтверждённых «исправлено»; остаточные риски явно назначены и объяснены |

M1/M2/M3 можно достигать по частям. Операционные улучшения не откладываются до завершения рефакторинга, а первый исправленный mobile можно выпустить до готовности сложного JWT-протокола при сохранении действующей совместимости.

**С чего начать практически**

Первая группа изменений для подготовки: P00; mobile runtime основа и editor fix P01; auth callers P02; codec P03 и runnable query P04; permit lifecycle P05; dependency PR P06. Одновременно эксплуатация проверяет имеющиеся backup и начинает P07/P08/P09. Backend после быстрых fixes берёт P12, затем P13.

Проектирование P11 и P16 начинается сразу, но их production enforcement/массовая миграция следует за доказательством совместимости и восстановления. P18/P19 выполняются по отдельным сценариям, пока остальные части продукта продолжают выпускаться.

Календарные сроки определяются после P00, назначения исполнителей и выбора первого набора сценариев. Для оценки каждого пакета отдельно учитываются разработка, тесты, существующие данные, выпуск и период наблюдения. Один общий срок «на весь рефакторинг» до этих уточнений будет ненадёжным. Следующий управляемый шаг — превратить P00–P06 в небольшие задачи с владельцами, а P07/P11/P16 начать как параллельные подготовительные работы.

**Матрица покрытия замечаний**

| Замечание аудита | Пакеты реализации | Доказательство закрытия |
|---|---|---|
| F01 — другой заказ в редакторе | P01, P21 | Детерминированный A/B race test + native upgrade/smoke |
| F02 — пустой retry payload | P03, P04 | Round-trip/transport failure + классификация старых payload |
| F03 — блокировка очереди исчерпанными попытками | P04 | N terminal + новая runnable, продвижение и replay |
| F04 — неполный DR Keycloak | P07, P09 | Полный restore с login и измеренными RPO/RTO |
| F05 — Docker socket trust | P08 | Consumers не могут выполнить изменяющий daemon API |
| F06 — циклы и проницаемые границы | P16, P18 | Явные APIs/владельцы + CI проверка запрещённых зависимостей |
| F07 — крупные сервисы/экраны/API | P18, P19 | Разделены перечисленные hotspots, сохранены транзакции и поведение |
| F08 — повторные уведомления | P13 | Несколько tick/реплик, batch progress, управляемый reminder |
| F09 — гонки performers | P12 | MySQL concurrency tests и совместимый cutover |
| F10 — непоследовательная доставка | P14, P15 | Durable intents, idempotency, crash/retry/UNKNOWN/reconciliation |
| F11 — преждевременный release permit | P05 | Actual running ≤ limit при disconnect/timeout/cleanup |
| F12 — refresh выдаёт expired token | P02 | Runtime matrix auth и сохранение offline session |
| F13 — незавершённый отзыв JWT | P11 | Старый JWT/старая сессия не пригодны после подтверждённого отзыва |
| F14 — dependency findings | P06 | Актуальные audits + clean build/runtime checks |
| F15 — ручные дубли контрактов | P20 | Один source, deterministic generation, old-client compatibility |
| F16 — пробелы runtime/сквозных проверок | P00, P01, P21 | CI исполняет реальные сценарии и обязательные проверки выпуска |
| F17 — нет подтверждённого внешнего alerting | P09 | Доставка/resolve сигнала при отказе всего Docker-host |
| F18 — безусловная readiness | P10 | Проверка итогового production image с Chromium/OCR fixtures |
| F19 — бизнес-логика контроллера | P17 | Общий application use case с проверяемыми правами и транзакцией |
| F20 — Telegram до commit/неподтверждённый TTL | P13 | Commit/transport/crash cases, корректные delivery/TTL/counters |
