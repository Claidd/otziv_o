# Выполнение архитектурных исправлений — 8 сентября 2026 года

Выполнен и проверен локально первый практический пакет из [плана](ARCHITECTURE_CORRECTION_PLAN_2026-09-08.md): исправления A01, A03–A08, A10–A11 и первая измеряемая волна A02/A09/A12. Общий архитектурный долг и эксплуатационная приёмка остаются отдельной работой. Этот реестр различает реализацию, локальную проверку, CI и выпуск.

Исходная точка: HEAD `3458e56f29ec0288925f2a4795105fe863da5158` вместе с существовавшим рабочим деревом. До правок сохранены `initial-git-status.txt` и `source-before.json` (3907 файлов). Предшествующие изменения сохранены. В рамках этой работы коммит, push и production deploy не выполнялись.

## Статус A01–A12

| Пункт | Владелец по роли | Реализовано в этой волне | Приёмка и остаток |
|---|---|---|---|
| A01 | Backend | Три узких API identity/reminders/messaging; скалярные данные вместо чужих ORM-моделей; сохранена новая транзакция после commit | Architecture gates и MySQL transaction regression прошли в полном backend suite. [ADR008](ADR-008-WORKER-RISK-NOTIFICATION-APIS.md) |
| A02 | Backend / архитектура | Четыре внешних обращения к NextOrderRequestRepository заменены API владельца; сохранены атомарность, порядок locks и пакетные чтения | **Первая волна, общий долг открыт:** −4 foreign persistence и −12 internal allowances в этом сценарии. 285 целевых tests PASS, включая 3 MySQL. Другие границы требуют разработки. [ADR011](ADR-011-NEXT-ORDER-REQUEST-BOUNDARY.md) |
| A03 | Gateway / backend messaging | Постоянный inbound inbox до HTTP, повторная доставка, history recovery; receipt и бизнес-эффект в одной транзакции; постоянный outbox ответа | Linux gateway 136/136 PASS; backend целевые tests и MySQL PASS. Остаются coordinated cutover, catch-up доступной истории и эксплуатационные alerts. Внешний outreach receiver не реализован этим репозиторием. [Runbook](WHATSAPP_INBOUND_DELIVERY_RUNBOOK.md) |
| A04 | Backend messaging | Короткие prepare/finalize TX, неизменяемый снимок отправки и прежний operation ID; provider вне TX; receipt-only UNKNOWN recovery; fresh fail-closed live switch и общий DB budget guard | MySQL 6/6 и протокольные tests PASS. На свежем дампе две прежние UNKNOWN сохранены без rearm. Нужна нагрузочная и эксплуатационная приёмка. [ADR009](ADR-009-SCHEDULED-DELIVERY-TRANSACTIONS.md) |
| A05 | Infrastructure | Ingress не ожидает healthy Grafana; контракт защищает production/local/dev Compose | Production Nginx template проверен без Grafana: сайт, API health, OIDC и login page доступны; маршрут Grafana восстановился без рестарта ingress. Штатный local Docker smoke PASS |
| A06 | Gateway / эксплуатация | Постоянные записи сохранены; индекс вместо обхода каталога на каждом admission; лимит вместимости; метрики; Linux single-writer flock; DR restore fence и CLI проверки | Linux gateway 136/136 PASS, crash/single-writer/DR cases без пропусков; фактический Chromium runtime PASS. Полный нагрузочный профиль, пороги и independent DR acceptance открыты. [Runbook](../whatsapp/OPERATION_LEDGER_RECOVERY.md) |
| A07 | Web / identity | Временный refresh failure сохраняет ещё действующий token; ограниченный single-flight retry; защита поколения сессии; запись не переотправляется | 42 auth runtime tests PASS; actual bundled Keycloak SDK: HTTP 503, network failure и invalid_grant — 3 browser PASS. Старый bundle воспроизвёл 2 FAIL, новый их устранил |
| A08 | Web / mobile / payments | Latest-query ownership для bootstrap/filters/page/refresh; mobile lifecycle; единая серверная сортировка LIVE/ARCHIVE с устойчивой парой createdAt/id; editor epoch сохраняет новый редактор после позднего save/error предыдущего | MySQL сортировка 6/6 PASS. Journal runtime web 12 / mobile 16 PASS. Browser web 4 / mobile 2 PASS, включая воспроизведённый и исправленный late-save A→B. Записи не переотправляются |
| A09 | Backend / клиенты | Overdue query/read model вынесены из HTTP controller; общие task loaders без дублирования правил; payment-link state вынесен из OrderDetails | **Первая волна, общий долг открыт.** Controller/query 62/62 PASS; client state/route tests PASS. ADR012 показывает +7 reviewed read dependencies и −2 прежние связи, net +5 internals; это не сокращение общего долга. Остальные hotspots требуют разработки. [ADR012](ADR-012-WORKER-OVERDUE-QUERY.md) |
| A10 | Storage / orders | Registry до PUT; повторная авторизация; атомарные URL/ATTACHED/cleanup intent; постоянная защита живых ссылок; recovery после crash и позднего PUT | 49 security/storage tests PASS, включая MySQL; новая схема успешно применена к свежему дампу. Исторические orphan-объекты не сверены и не удалялись; рост tombstones и производительность требуют приёмки. [ADR010](ADR-010-REVIEW-PHOTO-LIFECYCLE.md) |
| A11 | Security / storage | Legacy и manager upload используют общий scope-checked lifecycle; запрет до PUT и повторная проверка перед commit; unavailable отображается в HTTP 404 | Настоящий SecurityConfig/MVC chain: запреты для чужого review, разрешённый actor и default legacy=false проверены. Входит в 49 photo/security tests |
| A12 | Backend API / оба клиента | Public-payments transport scope переведён на generated input/output + общий runtime adapter; facade делегирует feature API. Решение по AI зафиксировано отдельно | **Первая волна, общий долг открыт.** Generation/check 187 operations / 204 schemas, shared 38 tests и Android 73 DTO compatibility PASS. Остальные DTO families и 23 обратных feature→ApiService импорта требуют разработки. [AI ADR013](ADR-013-SPECIALIZED-AI-PROVIDER-BOUNDARY.md) |

## Проверки общего кандидата

Корень приватных доказательств: `.codex-tmp/correction-run-20260908/`. Клиентские полные логи и manifests также находятся в `.codex-tmp/architecture-audit-2026-09-08/client-final-*`.

| Проверка | Результат / граница |
|---|---|
| Backend, JDK 26 | 690 suites / 4798 tests: **4783 PASS, 0 failures, 0 errors, 15 skipped**. Все test phases завершены; package/verify завершены отдельной командой после разрешения Maven cache issue, см. ниже |
| Architecture | ModuleBoundary, ModuleDependencyPolicy и ModuleEncapsulation PASS без отключения правил. Фактический observed: 3000 internals (было 3015), 301 foreign persistence (306), 222 module edges / 207 cyclic edges (без изменения). Метрики не складываются, циклы этой волной не устранены |
| Web / mobile / browser | Финальные web: 733 PASS (108 files); mobile: 230 Node + 197 runtime PASS (23 runtime files); обе production-сборки PASS. Общий browser: 31 PASS (web 20 / mobile-web 11), включая SDK refresh и journal. Все 115 Angular-файлов Docker web побайтно совпадают с проверенными; дополнительный штатный Nginx 50x.html записан отдельно в manifest |
| Shared contracts | 38 PASS; generation/check и Android 73 compatibility PASS; package/lock/shared source drift не найден. Это не signed native acceptance |
| Gateway | `whatsapp-all-linux.log`: 136 PASS / 0 skip, Linux Node 22, fixtures only и network none. `whatsapp-runtime-compatibility.log`: реальный Chromium, flock и lifecycle на новом образе; provider login не выполнялся |
| External-review worker | 32/32 source tests PASS. Новый образ прошёл реальные Chromium и offline English/Russian OCR readiness, disconnect cleanup удерживает admission до завершения. Исправлен неполный deploy bundle новых WhatsApp modules: runtime/probes/recovery CLI и транзитивные require/require.resolve проверяются контрактами |
| Flyway | Имена/версии уникальны; хеши всех существовавших миграций неизменны. Append-only текущей работы проверен по source-before, а не пустым diff HEAD→HEAD |
| SQL guard | 1910 Java files PASS. 23 causal checks проверяют узкое исключение SQL004: точный файл/выражение/хеш и Sort.Direction→ASC/DESC; изменение whitelist снова даёт FAIL |
| Infrastructure | Contract PASS; production ingress proof PASS; deploy release contract, lineage и source bundle closure PASS. Изменены только состав комплекта и его проверки; развёртывание не запускалось |

Первый `mvnw.cmd -o -B -ntp clean verify` выполнил весь набор tests за 25:42, затем завершился BUILD FAILURE на `spring-boot:repackage`: plugin dependencies были в локальном Maven cache с другим repository ID и не могли быть проверены offline. Следующий `mvnw.cmd -B -ntp -DskipTests verify` завершился **BUILD SUCCESS за 12 секунд**. Java-исходники backend и скомпилированные классы не менялись; Maven подтвердил `Nothing to compile`. Повторный запуск завершил упаковку, он не считается повторным выполнением tests. Сохранены оба исходных лога, XML-отчёты и их ZIP.

15 skips существовали до этой волны: 13 относятся к явно отключённому локальному AI-fallback; один — opt-in rehearsal точной старой схемы v217; один — opt-in Keycloak issuer protocol runtime. Они перечислены в `backend-skips.json` и не учитываются как PASS. Upgrade свежей схемы v287→v309 и обычный login/token настоящего local Keycloak проверены штатным smoke; это другой scope, чем два пропущенных opt-in сценария.

`docker-backend-class-comparison.json` подтверждает побайтное равенство всех **3885** файлов BOOT-INF/classes и BOOT-INF/lib между упакованным проверенным backend и фактическим Docker jar, включая **2989** application classes. Архивные metadata/loader отдельно не объявляются побайтно одинаковыми. `docker-web-artifact-comparison.json` связывает все **115** Angular-файлов с фактическим Docker web image. Полные hashes jar, image IDs, исходников и logs сохранены в `final-evidence.json`.

## Штатный Docker и существующие данные

`prod-like-smoke.ps1 -NoDbAdmin -NoLogs` выполнил новый импорт с VPS и полную Compose build. Дамп проверен по SHA-256, восстановлен только в локальный MySQL; production credentials/messaging/payment masters санитизированы штатным скриптом до запуска приложения. Временный plaintext dump удалён скриптом. Режим OfflineAppBuild и fallback не использовались.

Первый общий прогон завершился PASS на `http://localhost:8088`: приложение, реальные local Keycloak login/token, security/capability routes, payment shadow, workload shadow и scheduled reconciliation. После последней клиентской правки web image пересобран; заключительный `prod-like-smoke.ps1 -NoBuild -NoDbAdmin -NoLogs` **снова получил свежий дамп** и завершился PASS на окончательных образах (`prod-like-final-client.log`). `-NoBuild` использовал уже пересобранные images и не отключал восстановление БД. Стенд оставлен работающим на `http://localhost:8088`.

Свежая схема была на `1.10.287`; приложение применило 16 доступных миграций до `1.10.309`, включая новые 307/308/309. До upgrade было 11859 scheduled rows: 11835 без delivery status, 22 SENT и 2 UNKNOWN. Сравнение количества и digest state IDs / delivery tokens / status / message подтвердило неизменность обеих UNKNOWN после upgrade; envelope для них остался NULL. Новый dispatch guard содержит одну строку; registry и прежняя S3 cleanup queue пусты. Это подтверждает upgrade существующей схемы, а не только пустой MySQL fixture.

Доказательства: `prod-like-final.log`, `fresh-db-before-upgrade.log`, `fresh-db-upgrade-proof.log`, `fresh-db-applied-migrations.tsv`, `scheduled-unknown-{before,after}-upgrade.tsv`. Точные SELECT сохранены в `scheduled-unknown-identity.sql` и `fresh-db-upgrade-migrations.sql`. В proof нет экспортированных текстов сообщений или пользовательских токенов; сравниваются агрегаты и digests.

## Связь со старыми F01–F20

Исторические отчёты сохраняют свои ревизии. [C11](C11_CI_PREFLIGHT_AND_BRANCH_POLICY_2026-09-08.md) уже документирует включённые 20 обязательных checks и проверенную защиту main; это не означает успешный CI текущего дерева. [C12](C12_RUNTIME_REMEDIATION_2026-09-08.md) и [C13](C13_PMA_ACTIVATION_AND_HISTORY_SCAN_2026-09-08.md) уточняют Alloy/MySQL/PMA: нельзя заново записывать опубликованный проверенный PMA как ещё не опубликованный или переносить старые C9/C10 результаты на новый кандидат. Эти server/scan observations в текущей работе повторно не получались.

| Старые пункты | Текущий вывод | Следующая необходимая работа |
|---|---|---|
| F01, F12 | Исходные client lifecycle/auth механизмы исправлены локально | Поддерживаемый минимум, исходный signing key, точный release APK и установка обновления; native/provider flows |
| F02, F03, F08, F09, F20 | Исходные commands/intents/fences реализованы | Повторная инвентаризация legacy непосредственно перед cutover, согласованный sender/receiver rollout и наблюдение |
| F04, F13 | Механизмы восстановления/отзыва и локальные protocol proofs есть | Независимый согласованный recovery set, реальные RPO/RTO, active issuer flows, shadow/bootstrap/drain и controlled enforcement |
| F05, F11, F18 | Исходные observer/runtime/lifecycle механизмы реализованы | Проверки и rollout точных выпускаемых образов; новый worker runtime уже проверен локально |
| F06 | **Осталась разработка**, соответствует A02 | Следующие owner API волны и конечный каталог разрешённых связей |
| F07 | **Осталась разработка**, соответствует A09 | Оставшиеся controller/service/client hotspots и замеры затронутых горячих путей |
| F10 | Исходные lead/outbound-operation дефекты исправлены | Sender/receiver rollout; более широкая гарантия не универсальна: известное окно publication afterCommit→reservation остаётся отдельным локальным сценарием для будущего outbox |
| F14 | **Осталась локальная и релизная работа** | Полные свежие dependency/image scans кандидата и разбор конкретных остаточных находок; прежние частичные security proofs не равны полной приёмке |
| F15 | **Осталась разработка**, соответствует A12 | Остальные transport families, затем совместимость поддерживаемого native minimum |
| F16 | Gates реализованы; required checks подтверждены C11; приёмка частичная | Новый точный hosted candidate, окончательные browser/runtime результаты и native/capacity acceptance |
| F17 | Monitor/alert механизмы реализованы | Независимый monitor, реальный получатель, доставленные firing/resolved и проверка молчания самого monitor |
| F19 | Прежние command-сценарии исправлены | Остаток query/controller относится к A09 и не отменяет уже выполненный вынос mutations |

## Условия следующей волны и выпуска

1. Использовать сохранённый source/artifact/test binding при подготовке следующего кандидата. Проверено 3962 файла исходного source scope; отдельно записаны build inputs вне исходного измерения. Одного HEAD недостаточно для воспроизведения этого рабочего дерева.
2. Продолжить A02/A09/A12 конкретными сценариями: companies/orders и billing/payments; оставшиеся query/controller и ManagerControl/mobile Manager; generated orders/manager API и удаление обратных feature imports. Первый пакет не закрывает этот локальный долг.
3. До выпуска выполнить security/hosted checks точного кандидата, сверку очередей и writer versions. UNKNOWN сверяется по прежнему operation ID; rollback не разрешает повторную отправку.
4. Для S3 сначала получить инвентаризацию исторических объектов/ссылок и отчёт принадлежности, затем применять защитный интервал и очистку доказанных orphan. Постоянные upload tombstones не удалять произвольным TTL.
5. Отдельно определить business load/latency, допустимый replay interval, владельцев RPO/RTO и alert recipients, поддерживаемый native minimum. Эти значения и внешняя приёмка не заменяются количеством tests.

Доказательства финализированы 8 сентября 2026 года после общего прогона. `source-after.json`, `source-changes.json`, `additional-build-inputs.json`, `backend-test-summary.json`, `backend-surefire-reports.zip` и `final-evidence.json` находятся в указанном приватном каталоге. В исходном измеряемом scope: 56 добавленных файлов, 78 изменённых и один удалённый тест прежнего in-memory deduplicator, заменённый durable receipt / MySQL regression tests. Документы и дополнительные build inputs учитываются отдельно. Независимое завершающее ревью не нашло новых обязательных локальных исправлений в пределах этой волны.
