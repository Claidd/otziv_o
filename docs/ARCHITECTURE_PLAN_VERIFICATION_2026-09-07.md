# Проверка выполнения архитектурного плана — 7 сентября 2026

> Это исходная повторная сверка до дополнительной реализации. Устранение найденных
> недоделок, новые доказательства и оставшаяся внешняя приёмка описаны в
> [актуальном дополнении](ARCHITECTURE_REMAINING_WORK_COMPLETION_2026-09-07.md).
> Дальнейшие находки и результаты в этом документе сохранены как история среза.

**На момент этой сверки все действия не были выполнены.** Проверены все 22 пакета P00–P21 из
[согласованного плана](ARCHITECTURE_REMEDIATION_PLAN_2026-09-07.md): исходные
нумерованные действия, текущий код, подключение компонентов, тесты, CI и сохранённые
результаты прогонов. Найдены незавершённые изменения кода и инструментов, а также
непроведённые проверки совместимости, восстановления и выпуска.

Прежнее утверждение «изменения кода по P00–P21 реализованы» было слишком широким.
Оно исправлено в [статусе реализации](ARCHITECTURE_IMPLEMENTATION_STATUS_2026-09-07.md).
Зелёные результаты тестов сохранены: они подтверждают проверенные сценарии,
но не означают выполнения всех требований плана.

В этой сверке код приложения не менялся, deployment/restore и полный suite повторно
не запускались. Проверены исходные отчёты и текущие исходники; отдельно выполнен
read-only `node contracts/generate.mjs --check` — PASS. Находки по lifecycle,
readiness и старым входам основаны на анализе действующего кода; новый runtime-прогон,
воспроизводящий каждый из этих сценариев, не заявляется.

## Матрица всех пакетов

«Реализовано локально» означает наличие кода и соответствующих локальных доказательств.
«Частично» означает конкретный остаток разработки, инструментов, документации или CI.
Отсутствие доказательства внешней приёмки не означает автоматически дефект внешней системы.
Общий процент готовности не вычисляется: объём действий и уровни приёмки различаются.

| Пакет | Подтверждено | Что не завершено или не доказано |
|---|---|---|
| **P00 — исходная точка** | P00.1–4: сохранены исходный diff и snapshots; Java 26, Maven wrapper, MySQL, клиентские/Node/infra checks и свежий local restore доступны и прошли. | **P00.5–6:** нет полного подтверждённого перечня реально используемых production-контуров, replicas/schedulers и поддерживаемых выпущенных mobile-версий. Локальные safety flags и debug upgrade fixture не заменяют эту инвентаризацию. |
| **P01 — мобильные редакторы** | P01.1–5: настоящий Angular runtime target/TestBed; session ID + generation; captured entity ID; отмена GET редакторов; A/B, ABA, old-error и dependent-category regressions. Первая часть P01.6 — поздняя запись не меняет новый редактор. | **P01.6:** при возврате к той же cached manager page нет обязательного чтения фактического состояния после неизвестного/позднего результата записи. Есть связанный незавершённый board loading/leave/enter сценарий. Native приёмка — P21.4. |
| **P02 — mobile auth** | P02.1–6 реализованы локально: typed refresh, временная/окончательная ошибка, shared refresh и generation, serialized storage, сохранение offline session, ограниченный GET retry, независимые public/optional-auth ссылки. Runtime/browser проверки пройдены. | Полная проверка на поддерживаемом выпущенном native-клиенте, OIDC/background/resume/push и наблюдение login/refresh после выпуска не завершены. Основную локальную auth-реализацию отсутствующей считать нельзя. |
| **P03 — lead codec** | P03.2–5: явный Jackson 2 + JavaTimeModule codec, валидация, версия, неизменяемое содержимое, одинаковый payload при повторе. | **P03.1 и приёмка:** не предъявлена сверка с реальным принимающим приложением и известной исторической payload fixture. Round-trip нового payload, прочитанного как v0, не является историческим образцом. |
| **P04 — retry и старые записи** | P04.1–4: bounded indexed claim, SKIP LOCKED, lease/token fencing, terminal/quarantine, backoff и audited replay. Безопасная политика неизвестной истории P04.6 присутствует. | **P04.5/7 — частично в коде:** dry run без cursor перечитывает первую страницу; нет полного отчёта IDs/категорий причин и actual changed counts. Нужны checkpoint/bounded execution и фактический разбор старой очереди; drain/cutover не выполнен. |
| **P05 — лимит реальной работы** | Permit удерживается до завершения отслеживаемой работы и cleanup; timeout/close не освобождают его досрочно. Есть drain, bounded watchdog и UNKNOWN для неоднозначной отправки. | **P05.2/6 — частично:** disconnect не подключён к отмене поддерживаемой browser/OCR работы; нет обещанных running/cleanup/timeout/rejected метрик и возраста cleanup. Наблюдение rollout не выполнено. |
| **P06 — зависимости** | Lockfiles/runtime integration обновлены; npm audits, actual image smokes, Trivy JSON/SBOM и проверка Java packages выполнены. Финальные backend/web: 0 HIGH/CRITICAL. | **P06.4–6/приёмка:** live WhatsApp login/session smoke не выполнен; vendor-unfixed HIGH/CRITICAL не имеют завершённого review с владельцем/сроком; полный production fleet, включая Keycloak/БД/monitoring images, не покрыт этим набором application-image checks. |
| **P07 — восстановление** | Реальный encrypted PostgreSQL backup → version-bound download → restore, consistency manifest CLI и отрицательные проверки ключа/повреждения: 13/13 PASS. | **P07.1–2/5–9:** фактическая карта копий/владельцев, независимое хранилище, retention/RPO/RTO, write fence пары БД, полный restore MySQL+Keycloak+app+objects+secrets/ledger и login/refresh/decryption не доказаны. Внешние аттестации manifest не исполняют эти действия. |
| **P08 — Docker monitoring access** | P08.1–4 и конфигурационная часть P08.5: единственный socket owner, deny-by-default proxy, sanitization, отдельная сеть, consumers без socket. Реальные Dozzle/Alloy fixtures проверены. | **P08.5/приёмка:** production rollout consumers и фактический поток логов после переключения не выполнены/не доказаны. В изученной локальной границе кодового пробела не найдено. |
| **P09 — внешнее оповещение** | Runner, bounded HTTPS probes, durable pending alert, dedup/resolve, maintenance, deadman и backup freshness реализованы; mock receiver tests есть. | **P09.3 — код:** реальные collectors runtime-метрик и защищённая HTTPS-публикация не подключены. **P09.1–2/4–6:** внешний host/provider/contact, пороги, receiver/deadman и реальные outage/backup/channel drills отсутствуют. |
| **P10 — Chromium/OCR readiness** | P10.1–4: startup/readiness, фактические Chromium sandbox и OCR probes, ограниченный startup, non-root/read-only image smoke и отрицательные fixtures. | **P10.5 — код:** backend не проверяет readiness до claim; 503 неготового worker превращается в обычную неуспешную попытку. Production host preflight и rollout также не выполнены. |
| **P11 — отзыв сессий** | Durable sid/epoch binding, locked recheck, revocation/mutation protocol, password UNKNOWN и startup gates реализованы. Отдельный тест с настоящим Keycloak 26.2.5 — PASS. | **P11.5/7/8 и выпуск:** recovery point, фактические shadow coverage/bootstrap/drain/enforce, capacity и old-mobile/new-strict-backend matrix не доказаны. В локальном контуре enforcement выключен. |
| **P12 — переходы performers** | Общий владелец переходов, canonical locks, active-offer uniqueness и MySQL concurrency tests есть. В свежей локальной схеме дубликатов активных предложений не найдено. | **P12.4/6 и приёмка:** preflight/решения по конфликтам целевого production-контура и drain перед переключением не предъявлены. Среди изученных real-MySQL tests отдельно не найден named accept/decline race из плана. Это пробел evidence, не доказательство неработающей блокировки. |
| **P13 — delivery/TTL performers** | P13.1–5: durable intents в TX, generation identity, claim/fencing, due selection, TTL после подтверждения, UNKNOWN и операторское разрешение. | **P13.6 — код/tooling:** V288/V300 переносят historical data массовым SQL без обещанных bounded dry run/checkpoint. **P13.7:** нет фактической сверки старых OFFERING/READY и решений по истории; dispatcher activation не выполнен. |
| **P14 — исходящие leads** | P14.3: frozen command сохраняется вместе с бизнес-изменением; relay выполняет provider call вне TX. Неподготовленный общий outbox не включён. | **P14.1–2/5–7:** использование целевого receiver, подтверждённый контракт persistent dedup/version/order и cutover не доказаны. Один Idempotency-Key отправителя не доказывает поведение получателя. |
| **P15 — WhatsApp operation identity** | Keyed paths: durable registry, envelope hash, exclusive claim, receipt, UNKNOWN/reconciliation, restart/drain и stable operation ID у переведённых producers. V301 manager reply recovery реализован. | **P15.7 — код/миграция:** старые callers остаются, adapter создаёт новый UUID при каждом таком вызове; измерение и устранение legacy usage не реализованы. Live provider smoke/reconciliation и rollout не выполнены. |
| **P16 — модульные границы** | Карта владельцев, базовая схема, сценарные каталоги, ADR-001, ArchUnit, reviewed repository baseline и запрет возврата удалённых разрешений присутствуют. | **P16.4/6 — частично:** нет полного набора решений auth/money/delivery/contracts/deploy/recovery и явной связи MVC/REST; общий ratchet охватывает repositories, но не весь обещанный scope внутренних классов/новых циклов выбранных модулей. |
| **P17 — команды вместо controller logic** | P17.1–3 и следующая волна P17.5 выполнены для всех 29 worker HTTP mutations: 9 владельцев, explicit actor, locked permissions, audit/cooldown и real-MySQL races. | **P17.4 — код:** связанные manager/MVC входы продолжают независимо координировать status/publication transitions. Общий нижележащий OrderService не заменяет единого полного application use case. |
| **P18 — финансовые/manager сценарии** | Все три названных hotspots разделены; public API, monetary TX/lock order и авторитетная реализация перенесённых правил сохранены. Proxy/MySQL tests есть; владельцы и условия удаления compatibility facades документированы. | **P18.1 и performance acceptance:** нет полного before/after latency/query baseline важных financial/manager сценариев. Есть полезные отдельные queue/N+1 measurements; они не закрывают этот объём. Production rollout не выполнен. |
| **P19 — клиенты и страницы** | P19.2–5: manager editors, четыре названных order-details сценария, web dictionary domains имеют владельцев state/errors/lifecycle; mutable state scoped к странице. Guard P19.7 есть для извлечённых facades. | **P19.1 — код:** named manager/orders/payments API domains перенесены не полностью. **P19.6:** manager board GET/loading/re-entry не завершены. Ограниченный guard не делает ещё не перенесённые page calls узкими API. |
| **P20 — общие контракты/helpers** | Детерминированный versioned Java → OpenAPI/TS механизм, transport adapters и clean-checkout/Docker package wiring есть. Общие два pure helpers имеют один source; unknown statuses безопасно обрабатываются в реализованных read-контрактах. | **P20.1–2 — объём:** только 5 GET endpoints/6 response schemas; write requests, errors/pagination/permissions и большинство DTO ещё ручные. **P20.5:** нет versioned supported-release fixtures/rollback matrix. **P20.7:** нет требуемой таблицы поведения различающихся routing helpers. |
| **P21 — проверки и выпуск** | Regression/runtime/browser/MySQL/architecture/native-storage/image/infra checks существуют; сохранены source/image/APK hashes и отчёты. Deployment lineage implementation и отдельный regression script есть. | **P21.4–8:** supported signed native upgrade/full OIDC/resume/push и условный iOS gate; SLO/saturation; CI подключение lineage regression и required-check policy; полный DR/alert/rollback drill; F01–F20→PR→owner→migration→rollout/observed-close registry не завершены. |

## Конкретные остатки реализации

### 1. Возврат на мобильную доску — P01.6/P19.6

В [ManagerPage.load](../mobile/src/app/features/manager.page.ts) (строки 3739–3770)
GET выполняется через `firstValueFrom` без lifecycle cancellation. При leave
(строка 2305) `loadEpoch` увеличивается, но board request не отменяется и `loading`
не сбрасывается. Поздний `finally` уже не совпадает с поколением и не снимает loading.
При enter той же cached page (строка 2277) новый GET вызывается только при смене
раздела либо подходящем nav intent.

Последовательность «началась загрузка → уход → поздний ответ → возврат в тот же
раздел без нового intent» оставляет путь к зависшему loading/устаревшей доске.
Для поздней записи редактора также отсутствует обязательный признак необходимости
сверки и чтение серверного результата при возврате. Имеющийся
[тест leave](../mobile/src/app/features/manager-editors.runtime.spec.ts)
(строка 237) отменяет debounce **до отправки** GET; указанный случай не покрывает.

Остаток: завершить cancellation/loading/re-entry и сверку неизвестной записи,
добавив тесты реального Angular компонента на эти последовательности. Автоматический
повтор мутации для этого не нужен.

### 2. Инструменты старых очередей — P04.5/7 и P13.6

[LeadCommandAdminController](../backend/src/main/java/com/hunt/otziv/l_lead/controller/LeadCommandAdminController.java)
(строка 33) принимает лишь `dryRun` и `limit` до 500.
[LeadCommandRepository.legacyBatch](../backend/src/main/java/com/hunt/otziv/l_lead/repository/LeadCommandRepository.java)
(строка 78) всегда выбирает `LEGACY ORDER BY id LIMIT ?`. Повторный read-only
dry run большой очереди перечитывает первую страницу. Отчёт возвращает counts
состояний без ID и отдельных причин. В режиме изменения count увеличивается
до CAS (controller:48), boolean фактического изменения игнорируется. Сам CAS
защищает запись, но отчёт не доказывает actual changed count.

[V288](../backend/src/main/resources/db/migration/V1_10_288__performer_scoped_delivery.sql)
(строки 48/54) выполняет полный `INSERT SELECT` для WAITING_PUBLICATION/OFFERED;
[V300](../backend/src/main/resources/db/migration/V1_10_300__performer_readiness_intent_index.sql)
(строка 15) — полный UPDATE. Это не обещанный управляемый backfill с dry run,
ограниченными пачками, checkpoint и возможностью остановки.

Остаток: инструменты полного read-only прохода и возобновляемого backfill,
проверка отчётных counts, затем классификация и решения на реальном backlog.
Миграции, которые уже применялись, сохраняются append-only; план исправления
должен учитывать как ещё не обновлённые, так и уже обновлённые БД.

### 3. Lifecycle telemetry и readiness admission — P05/P09/P10

В обоих [worker limiter](../backend/external-review-worker/src/task-limiter.js)
и [WhatsApp limiter](../whatsapp/task-limiter.js) (строка 8) есть внутренний `active`,
но нет обещанной instrumentation running/cleanup/timeout/rejected и cleanup age.
Worker disconnect также не сигнализирует отмену поддерживаемой browser/OCR работы;
работа остаётся ограничена permit/deadline, однако P05.2 выполнен не целиком.

[export-signals.mjs](../infrastructure/monitoring/export-signals.mjs) (строка 19)
читает уже готовый `runtime-metrics.json`. Он не собирает runtime metrics и не
публикует HTTPS endpoint. Необходимость этих компонентов прямо указана в
[README мониторинга](../infrastructure/monitoring/README.md) (строки 13/25).

[ExternalReviewCheckService.processOne](../backend/src/main/java/com/hunt/otziv/external_review_checks/service/ExternalReviewCheckService.java)
(строка 150) проверяет runtime switch и делает claim. Затем
[ExternalReviewWorkerClient](../backend/src/main/java/com/hunt/otziv/external_review_checks/service/ExternalReviewWorkerClient.java)
(строка 27) сразу выполняет POST; readiness не читается. Worker `/ready` существует,
но его 503 попадает в общий failure path с расходованием попытки.

Остаток: instrumentation и защищённый сбор сигналов, wiring readiness в admission
backend, проверки отказа без захвата/расходования задания, затем реальные внешние
пороги, получатель и drills. Работающий mock alert receiver этого не заменяет.

### 4. Неполный переход producers и application entry points — P15/P17

Старые трёхаргументные методы
[WhatsAppServiceImpl](../backend/src/main/java/com/hunt/otziv/whatsapp/service/WhatsAppServiceImpl.java)
(строки 143/196) создают `UUID.randomUUID()` на каждом вызове. Callers остаются в
[LeadServiceImpl](../backend/src/main/java/com/hunt/otziv/l_lead/service/LeadServiceImpl.java)
(строка 1268) и
[SendMessageController](../backend/src/main/java/com/hunt/otziv/whatsapp/controller/SendMessageController.java)
(строка 40). Формально keyed HTTP request не сохраняет идентичность повторной
бизнес-операции. Измеритель использования legacy paths не обнаружен.

Worker status command содержит полный сценарий, но
[ApiManagerOrderController](../backend/src/main/java/com/hunt/otziv/manager/controller/ApiManagerOrderController.java)
(строка 73), [OrderController](../backend/src/main/java/com/hunt/otziv/p_products/controller/OrderController.java)
(строка 266) и
[OrderDetailsController](../backend/src/main/java/com/hunt/otziv/p_products/controller/OrderDetailsController.java)
(строка 273) самостоятельно координируют status/publication transitions.

Остаток: inventory/measurement и миграция старых producer paths на durable business ID;
единый use case для действительно одинаковых операций разных transport-входов с
сохранением различий прав/HTTP/form contracts. Это не требует механического вызова
worker-specific команды из любого manager-входа.

### 5. Ширина клиентских API и контрактов — P19/P20

AST текущего [ApiService](../mobile/src/app/core/api.service.ts) показывает
292 метода, из них 158 напрямую используют HttpClient. Это не самостоятельная
метрика качества: конкретный остаток — manager board/orders/details,
public/group payments и payment administration HTTP operations, в частности
строки 3663, 3890, 4526 и 4625. Наличие compatibility facade допустимо, но
перечисленные предметные HTTP-операции ещё не перенесены целиком.

[Order editor config](../contracts/order-editor.config.json) и
[billing/payments config](../contracts/billing-payments.config.json) описывают
**5 GET paths и 6 response schemas**. Механизм generation реален, однако большинство
write DTO, errors, pagination и permissions-контрактов вне его scope.
[Contracts README](../contracts/README.md) прямо указывает ограниченный первый
набор и отсутствие доказательства installed released APK compatibility.
Java contract tests сериализуют records через отдельно созданный mapper;
проверка actual HTTP runtime JSON всего заявленного scope ещё не выполнена.

Два identical helper действительно объединены. Для отличающихся
[web routing](../frontend/src/app/shared/manual-payment-routing.ts) и
[mobile routing](../mobile/src/app/shared/manual-payment-routing.ts) не найдена
обещанная таблица поведения с фиксацией обоснованных различий.

Остаток: закончить перечисленные API domains, распространить проверяемые контракты
на обещанные данные/операции, привязать fixtures к реально поддерживаемым mobile и
backend версиям в обоих направлениях rollback window, сравнить routing helpers.
Большой HTML-шаблон сам по себе не объявляется невыполненным пунктом плана.

### 6. Архитектурные правила, performance и CI — P16/P18/P21

Есть [ADR-001](ADR-001-MODULE-BOUNDARIES.md) и полезные runbooks/каталоги, но не весь
обещанный набор архитектурных решений и объяснение совместного существования MVC/REST.
[ModuleBoundaryTest](../backend/src/test/java/com/hunt/otziv/architecture/ModuleBoundaryTest.java)
(строка 145) вводит общий ratchet для `.repository.` и отдельные конкретные запреты;
он не блокирует весь обещанный класс новых внутренних зависимостей и циклов.
Наличие **309 разрешённых legacy repository edges** само по себе не является
невыполнением P16: план разрешает постепенную миграцию с reviewed baseline.

Финансовая декомпозиция подтверждается содержательными тестами. При этом
[каталог финансовых сценариев](FINANCIAL_SCENARIO_BOUNDARIES.md) (строка 78)
не содержит полного before/after latency/DB baseline важных сценариев.
Queue benchmark со 100k history и одна membership batch projection полезны,
но не доказывают financial latency, throughput/saturation или согласованный SLO.

Release lineage реализован в
[DeploySnapshot.ps1](../infrastructure/scripts/prod/DeploySnapshot.ps1) (строка 118),
его причинные Git regressions есть в
[check-deploy-release-contract.ps1](../infrastructure/scripts/security/check-deploy-release-contract.ps1)
(строка 155). Этот script не вызывается текущим
[quality-gates.yml](../.github/workflows/quality-gates.yml) или его
`check-infrastructure-contract.ps1`.
[R10 required-check runbook](R10_CI_AND_REPOSITORY_CLEANUP.md) (строки 7–15)
не обновлён под новые checks; actual branch rules не подтверждены. Path-filtered
workflow нельзя объявлять обязательным для любого PR без корректной общей gate policy.

Остаток: дополнить decisions/enforcement выбранных модулей, выполнить отсутствующие
targeted performance measurements, подключить lineage regression, согласовать и
проверить required checks, завершить реестр F01–F20 с PR/владельцами/rollout evidence.

## Что подтверждено прогоном, а что им не проверено

Основные evidence сохранены в игнорируемой
`.codex-tmp/architecture-remediation-20260907/`. Исходные результаты не переписаны.

| Проверка | Подтверждённый результат и граница |
|---|---|
| Полный backend | `windows-final-clean-verify.log`: **4575 tests, 0 failures/errors, 15 skipped**, Java 26 и настоящий MySQL; BUILD SUCCESS 10:10:58 UTC. 658 XML совпадают с итогом. Это сохранённый полный прогон, не новый запуск при сверке. |
| Совпадение source/evidence | Manifest 3113 backend/contracts/shared files, SHA-256 `a94a943b7919c215cf6f9e9e2c26dfdf7777130449c4724a9a431731f87bdb1b`. Повторная read-only сверка: единственная последующая разница — удаление неиспользуемого baseline разрешения PaymentLinkService → OrderRepository. Для stricter baseline 309 отдельно прошли 5/5 ArchUnit tests на неизменённом байткоде. |
| Штатный local deployment | `local-final-fresh-smoke.log`: свежий VPS dump `prod-20260907-174950.sql.gz`, restore/build/health/public routes и safety PASS. V299/V300/V301 применены; дубликаты активных предложений 0. Десять проверенных queue/state tables пусты: это не historical backlog/replay/load proof. |
| Клиенты | Web 684 tests/102 files; mobile 128 runtime/17 files + 227 unit; production builds PASS. Chromium web/mobile-web 22/22; production-artifact hashes без расхождений. |
| Android | JVM/lint/assemble PASS; actual native storage/logout/crash 9/9; health/discovery/unsolicited callback PASS. Debug upgrade fixture не доказывает upgrade поддерживаемого production-signed APK. Полные OIDC/resume/push/iOS acceptance отсутствуют; подробности в native report. |
| Recovery CLI | 13/13: PostgreSQL → AES-GCM → TLS MinIO exact version → restore, 100 synthetic users/roles. Companion MySQL/objects/secrets/signing attestations synthetic; полноценный согласованный system restore не доказан. |
| Security | Полный secrets scan 140,41 MB: 0 findings, без новых исключений. Финальные backend image + 346 Java packages и web: 0 HIGH/CRITICAL. Worker/WhatsApp/observer имеют соответственно 91/105/56 vendor-unfixed HIGH/CRITICAL; доступных fixes 0, но risk acceptance не завершён. |

15 пропусков полного backend suite существовали до этих изменений: 13 отключённых
старых ожиданий AI fallback, opt-in V217 upgrade rehearsal и opt-in Keycloak runtime.
Последний выполнен отдельно успешно. Пропуски не скрываются и не считаются
прошедшими тестами полного suite.

Подробные границы: [Android](NATIVE_ANDROID_VERIFICATION_2026-09-07.md),
[Recovery CLI](RECOVERY_CLI_LOCAL_VERIFICATION_2026-09-07.md),
[rollout](ARCHITECTURE_ROLLOUT.md),
[financial scenarios](FINANCIAL_SCENARIO_BOUNDARIES.md),
[ManagerControl](MANAGER_CONTROL_SCENARIO_BOUNDARIES.md),
[worker commands](WORKER_COMMAND_BOUNDARIES_2026-09-07.md).

## Условия полного закрытия

После оставшейся разработки нужны доказательства на целевых данных и средах:

1. Установить фактически активные контуры, replicas/schedulers, поддерживаемые mobile
   и backend версии, владельцев receiver/storage/alerting и измеримые RPO/RTO/SLO.
2. Провести полный dry run и управляемую обработку старых очередей; сохранить IDs,
   причины, checkpoint и actual changed counts без payload/PII в публичных отчётах.
3. Проверить receiver persistent dedup/version ordering, согласованный cutover и
   UNKNOWN reconciliation; live provider smoke выполнять как отдельную разрешённую
   операционную проверку, не как CI-отправку реальным пользователям.
4. Выполнить независимый согласованный DR всех компонентов с login/refresh/decryption,
   фактический внешний alert/resolve при отказе Docker-host и репетицию rollback
   конкретного набора release artifacts.
5. Проверить поддерживаемый signed native upgrade, OIDC/PKCE/resume/logout/refresh/push
   и iOS gate, если эта платформа выпускается; подтвердить совместимость rollback window.
6. Выпустить изменения с необходимыми drain/bootstrap/shadow gates, проверить CI/lineage,
   сохранить exact commit/image/schema/SDK/APK evidence и завершить наблюдение по F01–F20.

На момент сверки production deploy, enforce cutover и внешние отправки не выполнялись.
Готовность отдельных локальных реализаций не является закрытием всей программы.
