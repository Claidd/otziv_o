# Реализация архитектурного плана — 7 сентября 2026

Последняя реализация и доказательства находятся в
[итоговой доработке P00–P21](ARCHITECTURE_FINALIZATION_2026-09-07.md).
Ниже сохранена история прежних срезов; их открытые пункты и числа нельзя
приписывать последнему состоянию без чтения нового отчёта.

Актуальная [повторная сверка всех P00–P21](ARCHITECTURE_PLAN_RECHECK_2026-09-07.md)
подтвердила сохранённые результаты тестов, но обнаружила оставшиеся пропуски
кода, deploy, протоколов и доказательств. Весь исходный план ещё не выполнен.

Актуальное состояние дополнительной реализации по P01/P19, P04/P13,
P05/P09/P10, P15/P17, P16/P20 и P18/P21 приведено в
[отчёте о закрытии замечаний](ARCHITECTURE_REMAINING_WORK_COMPLETION_2026-09-07.md).
Он содержит новые исходники, тесты, fresh-copy maintenance и фактический остаток
внешней приёмки. Ниже сохранены результаты первой волны и последующей исходной
сверки; их прежние численные результаты не приписываются новой версии кода.

## Исторический срез перед дополнительной реализацией

План P00–P21 выполнен частично. Повторная сверка каждого пакета с исходными
действиями обнаружила незавершённые изменения кода, инструментов, документации
и CI наряду с непроведённой production-приёмкой. Прежняя формулировка
«изменения кода по P00–P21 реализованы» была слишком широкой и исправлена.
Подробные основания и остаток по номерам действий приведены в
[проверке выполнения плана](ARCHITECTURE_PLAN_VERIFICATION_2026-09-07.md).
Ниже сохранены подтверждённые результаты реализации и локальных проверок.
Исходные изменения пользователя сохранены. Production deploy и включение
внешних отправок не выполнялись.

Срез полного clean verify: 3113 файлов backend/contracts/shared, SHA-256 manifest
`a94a943b7919c215cf6f9e9e2c26dfdf7777130449c4724a9a431731f87bdb1b`.
На нём прошёл полный `clean verify` с Java 26 и настоящим MySQL:
**4575 tests, 0 failures/errors, 15 skipped**, 10:10:58 UTC, 16 минут 18 секунд.
658 XML-отчётов совпали с итогом Maven; на момент полного прогона расхождений
исходников с manifest не было. После него удалена одна устаревшая запись
архитектурного baseline (PaymentLinkService → OrderRepository): 310 → 309.
Все 5 ArchUnit-проверок прошли повторно в отдельной копии на том же проверенном
байткоде, 10:15:55 UTC. Основной код, исходный snapshot и доказательства полного
прогона сохранены без изменений; итоговая разница касается только более строгого
тестового ограничения.
Штатный `prod-like-smoke.ps1` повторно получил и восстановил VPS dump
`prod-20260907-174950.sql.gz`; сборка и итоговый smoke прошли, сервисы healthy
на `http://localhost:8088`.
Временный незашифрованный dump удалён штатным скриптом после проверенного restore.

## Покрытие плана

| Пакет | Реализовано и локально проверено | Незавершённые действия и приёмка |
|---|---|---|
| P00 | Изолированная Java 26, Maven wrapper без обхода Enforcer, full clean verify и fresh-restore smoke PASS; сохранены исходный diff и проверяемые снимки | P00.5–6: фактические production-контуры, replicas/schedulers и поддерживаемые выпущенные mobile-версии не установлены полностью |
| P01 | Session/generation редакторов, отмена GET редакторов, неизменяемые IDs мутаций, Angular и browser regressions | P01.6: сверка доски при возврате после неизвестного/позднего результата записи; native release upgrade |
| P02 | Typed refresh, сохранение сессии при временной сети, logout/callback fences, checked Android disk commit, публикация auth state после записи | Полный native OIDC, background/resume и released-client acceptance |
| P03 | Версионированный lead codec, даты/checksum, отклонение oversized/poison payload | Сверка исторических команд целевого контура |
| P04 | Bounded SKIP LOCKED claim, FIFO scope, lease/token, terminal states и audited replay | P04.5–7: cursor/checkpoint полного dry run, IDs/причины и actual changed counts; разбор реальных старых записей и drain |
| P05 | Permit удерживается до фактического завершения; drain и ограниченная очистка | P05.2/6: сигнал отмены поддерживаемой работы при disconnect и метрики running/cleanup/timeout/rejected; rollout |
| P06 | Обновления зависимостей, final image/JAR scans, SBOM и полный отчёт без сокрытия unfixed findings | Review vendor-unfixed HIGH/CRITICAL с владельцем/сроком; live WhatsApp session smoke и охват остальных production images |
| P07 | Шифрование backup, verified version-bound download, consistency manifest; реальный локальный recovery CLI 13/13 | Независимое внешнее storage, RPO/RTO, полный согласованный DR |
| P08 | Единственный Docker socket owner; ограниченный observer API, redaction; реальные Dozzle/Alloy | Production activation и наблюдение |
| P09 | Внешний runner, dedup/resolve, maintenance, deadman и backup freshness | P09.3: сбор реальных runtime-метрик и защищённая HTTPS-публикация; получатель, независимый контур и настоящий alert drill |
| P10 | Настоящие Chromium/OCR readiness и sandbox probes; broken runtime даёт 503 | P10.5: backend не учитывает readiness перед claim; usernamespace/seccomp preflight production host |
| P11 | Durable session binding/revoke, password ambiguity, точные online/offline targets; реальный Keycloak 26.2.5 PASS | Recovery point, drain/bootstrap/shadow/capacity и enforce cutover |
| P12 | Canonical assignment locks, conflict guards, уникальное active offer в MySQL | Preflight/drain непосредственно перед production migration |
| P13 | Persistent intents, TTL после доставки, UNKNOWN fencing, exact-generation V300 marker | P13.6: управляемый dry run/backfill вместо полного небатчевого SQL; legacy resolution и controlled dispatcher activation |
| P14 | Lead state + frozen command атомарны; provider вне TX | Доказательство persistent dedup принимающей стороны |
| P15 | Durable gateway ledger, stable IDs для переведённых producers, strict receipts/envelope hash; V301 reply recovery; MAX/Telegram без скрытого keyed retry | P15.7: старые callers создают новый UUID на каждый вызов; измерение и завершение перехода на stable keys; live provider smoke |
| P16 | Карта владельцев, базовая схема, один ADR, ArchUnit и reviewed repository baseline; удалённые связи не возвращаются автоматически | P16.4/6: полный набор решений, MVC/REST и более полный запрет новых внутренних зависимостей/циклов выбранных модулей |
| P17 | Все 29 worker HTTP mutations — 9 владельцев команд; actor до locked validation/audit/cooldown; исправлены RR races и HTTP cooldown subtype | P17.4: связанные MVC/API входы ещё содержат самостоятельные бизнес-переходы; унификация и rollout |
| P18 | Все три выбранных hotspots разделены по сценариям; public API и денежные TX сохранены, двусторонняя финансовая связь устранена; full verify PASS | P18.1: полный финансовый before/after latency/DB baseline не представлен; rollout |
| P19 | Mobile feature APIs/editor facades, order-details facades, web dictionary domain state; tests/build/browser PASS | P19.1: named API domains перенесены не полностью; P19.6: manager board GET/loading/re-entry; native acceptance |
| P20 | Один пакет двух чистых helpers; воспроизводимые Java → OpenAPI/TS contracts для 5 GET endpoints и 6 response schemas | P20.1–2/5/7: остальные DTO, writes/errors/pagination/permissions, supported-release fixtures и таблица различий routing helpers |
| P21 | Runtime/MySQL/architecture/browser/native/infra/image проверки и runbooks | Подключение lineage regression к CI, required checks, supported signed native upgrade, SLO/saturation, DR/alerts/rollback и F01–F20→PR→owner→rollout registry |

## Проверки

| Проверка | Результат и доказательство |
|---|---|
| Backend, общий срез | 4575 tests, 0 failures/errors, 15 skipped; `windows-final-clean-verify.log`, `final-integration-result.json`; 658 XML и 3113 source hashes сверены |
| Финальное ужесточение архитектуры | 5/5 PASS; `final-architecture-ratchet-tests.log`; удалено единственное неиспользуемое разрешение на чужой repository |
| Штатный local deployment | PASS; `local-final-fresh-smoke.log`, свежий VPS restore, сборка, health, public routes/capabilities и safety checks |
| Web | 684 tests / 102 files, production build PASS |
| Mobile | 128 runtime tests / 17 files + 227 unit, production build PASS |
| Chromium web/mobile-web | 22/22 PASS, 09:00 UTC; immutable production artifacts, 187 hashes без расхождений |
| Android | `testDebugUnitTest lintDebug assembleDebug` PASS; actual storage/logout/crash 9/9; health/discovery/unsolicited callback PASS |
| Recovery CLI | 13/13 PASS: реальный PostgreSQL → AES-GCM → TLS MinIO → exact version → restore; сверены 100 synthetic users/roles |
| Infra / shared contracts | 25 runtime/recovery/monitoring tests; 16 shared parity tests; генерация без drift; 382 уникальных Flyway migrations |
| Secrets | Полный directory scan: 140,41 MB, 0 findings, 09:52 UTC; существующая конфигурация без новых исключений |
| Dependency / image security | npm audits 0; окончательные backend image + 346 Java packages и web image: 0 HIGH/CRITICAL; worker/WhatsApp/observer fixed HIGH/CRITICAL 0, vendor-unfixed 91/105/56 |
| Свежая local schema / safety | V299/V300/V301 success, 10 indexes/uniques и 4 generated barriers; active-offer duplicates 0; 12 runtime flags и 24 DB safety settings PASS |

Обычный полный backend suite сохраняет **15 ранее существовавших пропусков**:
13 явно отключённых старых ожиданий AI fallback; отдельная V217 migration rehearsal,
требующая disposable-БД ровно на V217; opt-in Keycloak runtime.
Keycloak runtime отдельно выполнен успешно (online/offline/reset/revoke).
Изменения этой работы не отключают тесты и не заменяют MySQL на H2.

## Существенные результаты рефакторинга

- `CommonBillingService`: сохранены все 76 элементов public API, включая record.
  Отдельные владельцы lifecycle/settlement/cancellation/route/reconciliation/delivery;
  одна авторитетная реализация каждого перенесённого правила.
- `PaymentLinkService`: 420 строк совместимого фасада, 46 public методов и 4 record.
  Init, settlement, manual payment, owner approval и reconciliation имеют отдельных
  владельцев; request/callback сохраняют порядок Order → Link → Approval.
- `ManagerControlService`: 365 строк, все 17 public API сохранены; 13 новых владельцев.
  Независимая AST-проверка: 215 эквивалентных тел, 101 делегат, 0 дублированных правил.
- Worker commands: настоящие InnoDB REPEATABLE READ races первоначально воспроизводили
  запись после смены исполнителя. После Order lock теперь читаются текущие ownership
  и binding, включая manager/owner. Блокировки ограничены строками агрегата.
  Поздняя смена расписания также повторно проверяется под блокировкой.
- Audit: явный actor не подменяется пустым или привилегированным SecurityContext.
  Раскрытие реквизитов невозможно без успешного независимого commit аудита; проверены
  реальная ошибка INSERT и обрыв MySQL connection непосредственно перед commit.
- Delivery: неизвестный результат не объявляется неотправкой. Manager reply/V301
  использует stable key, receipt и проверяемый hash исходного envelope.
  Старый best-effort after-commit delivery не объявляется durable outbox.
- Query evidence: одна batch projection вместо N membership reads для 1/10/50
  successors. На synthetic fixture со 100k history lead claim 76,3 → 0,076 ms,
  performer readiness 948 → 0,339 ms. Это локальные замеры, не production SLO.

## Отчёты и границы приёмки

Подробности: [финансовые сценарии](FINANCIAL_SCENARIO_BOUNDARIES.md),
[ManagerControl](MANAGER_CONTROL_SCENARIO_BOUNDARIES.md),
[worker commands](WORKER_COMMAND_BOUNDARIES_2026-09-07.md),
[клиентские границы](CLIENT_DICTIONARY_BOUNDARIES_2026-09-07.md),
[Android](NATIVE_ANDROID_VERIFICATION_2026-09-07.md),
[локальный recovery CLI](RECOVERY_CLI_LOCAL_VERIFICATION_2026-09-07.md),
[правила выпуска](ARCHITECTURE_ROLLOUT.md),
[ADR-001](ADR-001-MODULE-BOUNDARIES.md).

Android evidence относится к debug APK SHA-256
`b24395b37ad75b1107136eae51c6c3c6eb1cbd84d81956763487a656a7e10b85`.
Полный OIDC через native Chrome не выполнен: автоматическая проверка разрешений
отклонила запуск с причиной «blocked by policy». Удаление временных AVD-каталогов
также отклонено без подробного объяснения. Обход не выполнялся; эмулятор остановлен,
каталоги сохранены. Это не signed production upgrade и не проверка iOS/push.

Внешние storage/alert endpoint и RPO/RTO ещё не предоставлены. Local recovery fixture
не доказывает независимость storage, production IAM/ObjectLock или совместное
восстановление MySQL, Keycloak, объектов, секретов и integration ledger.
Production enforcement/dispatch, native release и внешнее наблюдение не включались.

Диагностические логи, XML, SHA manifests, SBOM и исходный diff сохранены в
игнорируемой `.codex-tmp/architecture-remediation-20260907/`; native и recovery
evidence — в каталогах, указанных в соответствующих отчётах. Эти временные данные
не предназначены для публикации вместе с исходниками.
