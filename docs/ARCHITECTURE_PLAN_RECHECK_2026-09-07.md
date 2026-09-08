# Повторная сверка полного архитектурного плана — 7 сентября 2026

Это историческая сверка до последнего поручения. Её обнаруженные пропуски,
последующие исправления и текущие условия приёмки сопоставлены в
[новом отчёте P00–P21](ARCHITECTURE_FINALIZATION_2026-09-07.md).

**План P00–P21 выполнен частично.** Дополнительная реализация закрыла значительную
часть ранее перечисленных недоделок, но сохранились конкретные пробелы кода,
инструментов и проверок, а также незавершённая приёмка выпуска.

Основание — [исходный согласованный план](ARCHITECTURE_REMEDIATION_PLAN_2026-09-07.md),
его нумерованные действия и критерии приёмки. Эта сверка охватывает **все 22 пакета**,
а не только шесть групп из [предыдущего отчёта о доработках](ARCHITECTURE_REMAINING_WORK_COMPLETION_2026-09-07.md).
Последний сохраняет действительные результаты реализации и запусков; его нельзя
читать как подтверждение полного закрытия исходного плана.

## Как проверено

Три независимых направления сверили текущие production-вызовы, тесты, CI/deploy,
контракты и документацию. Основной проверяющий отдельно проследил четыре найденных
пути в коде, ограничение Keycloak, recovery tooling и отсутствие реестра закрытия.
Наличие класса, скрипта или успешного suite не принималось за доказательство всех
требований соответствующего пакета.

В **13:10:42 UTC** повторно проверены хеши 4561 файлов предыдущего итогового
манифеста: расхождений нет. Проверены исходный журнал полного backend-прогона,
674 XML-отчёта, отдельные поздние contract-metadata изменения и performance
артефакты. Результаты совпадают с сохранёнными доказательствами. Нового полного
прогона, развёртывания, восстановления БД или внешних отправок эта сверка не
выполняла. Найденные сценарии ниже установлены по потоку исходников; новое
динамическое воспроизведение для них не заявляется.

GitHub проверен отдельно GET-запросами в **13:15:30.815 UTC**: ветка `main`
по-прежнему не защищена. Изменений удалённой конфигурации не было.

Статус «локально выполнено» означает реализацию и локальную проверку указанного
объёма. Он не означает публикацию или завершение наблюдения в production.
Общий процент готовности не вычисляется: пакеты имеют разные объём и приёмку.

## Матрица P00–P21

| Пакет | Что подтверждено | Что осталось по исходному плану |
|---|---|---|
| **P00 — исходная точка** | **1–4:** snapshots, Java 26, Maven wrapper, настоящий MySQL, согласованные Node runtimes, tests/build и штатное локальное развёртывание. | **5–6:** полная фактическая инвентаризация production-контуров, backend-реплик и schedulers; перечень всех поддерживаемых mobile-релизов. Один наблюдённый production image и локальные safety flags не заменяют fleet inventory. |
| **P01 — manager editors** | **1–6 локально выполнены:** настоящие Angular runtime tests, ID/generation, отмена GET, captured ID записи, сброс loading и чтение при возврате; `PageWriteTracker` вызывает сверку после завершения записи прежнего посещения. | Native выпуск и проверка обновления поддерживаемых установок по **P21.4**. Прежнюю ошибку доски менеджера больше не считаем открытой; найденный похожий остаток относится к order-details, **P19.6**. |
| **P02 — mobile auth** | **1–6 локально выполнены:** временный/окончательный отказ, durable storage, shared refresh, generation, пригодность access token, ограниченный GET/HEAD retry, public/optional auth. | Полноценная native auth-приёмка с провайдером и наблюдение login/refresh/401 после выпуска. |
| **P03 — lead codec** | **1–3, 5:** явный Jackson 2/JavaTime codec, validation до INSERT, immutable JSON, одинаковое бизнес-содержимое повторов нового queue-пути. | **4 / контракт интеграции:** версия схемы payload есть, но версия сущности и порядок применения у получателя не завершены; общий остаток **P14.2/5**. Прямой HTTP-путь P14 обходит гарантии новой очереди. |
| **P04 — retry / legacy queue** | **1–4, 6:** bounded due claim, fencing, backoff, DEAD/UNKNOWN и audited replay. Для **5/7** добавлены полный high-water inventory, cursor, CAS, IDs/причины и точные счётчики. | Фактический разбор целевой истории и drain/cutover. Свежая локальная копия содержит **0 lead queue rows**; это не доказательство обработки непустой production-очереди. Непустые synthetic MySQL tests проверяют инструменты. |
| **P05 — реальные задачи** | **1–2, 5–6:** permit до завершения работы/cleanup, UNKNOWN, graceful shutdown, running/cleanup/timeout/rejected metrics; worker cancellation/watchdog. | **3–4 — код:** автоматический WhatsApp restart не ограничивает `destroy()` временем и после его ошибки запускает новый client без подтверждённого cleanup. Этот путь также затрагивает **P15.6**. |
| **P06 — зависимости** | Обновления manifests/locks, audits, CI thresholds, фактические application-image smokes, packaged-JAR/container scans и SBOM сохранены. | **1/3/5:** не завершена документированная оценка оставшихся advisories/overrides и принятие риска с владельцем/сроком. **4:** live WhatsApp session не проверена. **6:** packaged-JAR scan не доказывает проверку всего Maven build/test graph; отдельный актуальный успешный OWASP run не установлен. Не весь production fleet охвачен application-image scan. |
| **P07 — восстановление системы** | **3–4 и часть 8:** реальный PostgreSQL CLI backup, шифрование, TLS/versioned object download, restore, manifest validation и отрицательные проверки на disposable стенде. | **1–2, 5–9:** реальные независимые источники, согласованные RPO/RTO, наблюдаемый общий write fence; комплексный стенд MySQL+Keycloak+app+objects+secrets; login/refresh/decryption/object access и безопасное восстановление security state. Это незавершённые инструменты/доказательства, не только ожидание production deployment. |
| **P08 — Docker observer** | **1–4:** минимальный proxy allowlist, redaction, private network, единственный socket owner, реальные Dozzle/Alloy fixtures. Compose/config/runbook обновлены. | **5 — deploy-код:** штатный `deploy-prod.ps1` не build/up/recreate `docker-observer`, но запускает зависимые Dozzle/Alloy через `--no-deps`. Нужны исправление процедуры и проверка потока логов при последовательном переключении. |
| **P09 — внешнее оповещение** | Collector получает реальные runtime/queue/backup сигналы; защищённый HTTPS publisher проверен в контейнере. Реализованы stable alert ID, dedup/resolve, bounded silence, freshness/deadman protocol. | **1–2, 4–6 и внешняя часть 3:** независимый host/provider, владелец/contact, schedule, SLO/RPO thresholds, реальный receiver с подтверждением/дедупликацией и drills backend/host/backup/channel с доставкой ответственному. |
| **P10 — worker readiness** | **1–5 локально выполнены:** startup self-check, Chromium sandbox, offline OCR, runtime assets, image CI smoke, backend readiness **до claim**. | Проверка точного выпускаемого image на deployment host и наблюдение переключения. Прежнего кодового пробела readiness-before-claim больше нет. |
| **P11 — отзыв сессий** | **1, 6** и существенная часть **2–4:** sid/epoch binding, durable mutation/reconciliation, password fence, issuer lookup, scoped revoke; pinned Keycloak integration PASS. Off/shadow/enforce guards и bootstrap подготовлены. | **2–4 — неполный протокол внешних изменений:** live roles/enabled не сохраняют историю переключения туда и обратно. **5/7–8:** recovery prerequisite, mixed-fleet bootstrap/drain, shadow coverage/enforcement и полная клиентская матрица не завершены. |
| **P12 — performers transitions** | **2–5:** общий владелец, canonical locks, current-row checks, uniqueness и MySQL concurrency regressions; preflight/maintenance для **4/6** подготовлены. | **1:** нет сводной рабочей таблицы assignment/offer transitions и counter effects. В обязательных MySQL проверках не найден отдельный **accept/decline race**. Целевые conflict review/drain по **4/6** не предъявлены. Это пробел описания/доказательства, а не установленная поломка locks. |
| **P13 — performers delivery** | **1–5:** durable intent в TX, dispatcher вне TX, generation/claim, due selection, TTL после подтверждения, UNKNOWN. Для **6–7** готовы политика, restartable bounded maintenance, checkpoint и exact counts. | Review/решения по реальной старой истории и activation/drain. В свежем локальном preflight **0 assignments / 0 offers / 0 conflicts**; успешный проход не доказывает восстановление отсутствующей delivery history. |
| **P14 — исходящие leads** | Новый путь сохраняет frozen command вместе с бизнес-изменением, worker отправляет вне TX; неподготовленный общий R6 не включён. | **2/3/5/7 — код/протокол:** активный `/api/leads/sendToServer` делает прямой HTTP вызов; receiver persistent dedup/entity-version ordering не завершены, old-after-new не доказан. **1/6/7:** полный фактический scope и безопасный cutover одного sender не закрыты. |
| **P15 — WhatsApp identity** | **1–5:** durable backend ID, frozen encrypted envelope, persisted claim/receipt, UNKNOWN barrier, подтверждённый replay без send; legacy business producers используют сохранённые ключи. | **6:** автоматическая замена gateway session имеет cleanup-пробел P05. **7 / приёмка:** поэтапный выпуск и live session compatibility не выполнены. Наличие стабильных IDs подтверждено; прежнюю массовую генерацию UUID для каждого бизнес-повтора открытой не считаем. |
| **P16 — границы модулей** | **1–7 локально выполнены:** context/container, data owners/API, ADR, проверки foreign internals/unmapped owners/cycles, reviewed baselines и причинные negative fixtures. | Baseline содержит существующий долг: 3013 внутренних, 309 repository и 207 циклических рёбер. План допускает постепенное устранение; ненулевой baseline сам по себе не означает невыполнение P16. |
| **P17 — HTTP/application** | **1–5 локально выполнены** в указанном worker scope и связанных manager/MVC status/publication входах: общие commands, actor/ownership, locks, counters/audit и причинные MySQL tests. | Выпуск/наблюдение. Утверждение не распространяется на каждый контроллер проекта: такой тотальный перенос не был критерием этой волны. |
| **P18 — financial/manager hotspots** | **1–8 локально выполнены:** три указанных hotspots разделены по сценариям; narrow APIs, transaction/lock ownership, совместимые delegating facades и запрет обратных зависимостей. Настоящий before/after SQL/EXPLAIN/latency benchmark сохранён. | Production SLO/saturation — **P21.5**. Измерены три read-heavy сценария; это не доказательство ускорения каждого workflow или удаления всех N+1. |
| **P19 — feature API/screens** | **1–5, 7:** 84 метода перечисленных областей переведены в 14 API; production callers проверены. Manager/order-details owners и web dictionary tabs реально подключены; editor state ограничен страницей. | **6 — код:** order-details не перечитывает состояние после завершения POST прежнего посещения; GET на enter может успеть до commit. Требуется такой же полный lifecycle-сценарий, как уже реализован у manager. |
| **P20 — контракты/shared** | **1–4, 6–8:** 187 операций / 172 пути / 204 схемы, генерация из реальных Spring/Jackson типов, SDK 1.1.0 в обоих клиентах, transport adapters, clean-context install, общие helpers и routing parity. Последний реальный APK73 и production JAR проверены. | **5 / приёмка:** `minimumSupported:null`; нет fixtures реального минимального поддерживаемого клиента. Не завершена runtime матрица нового клиента с выбранным поддерживаемым rollback backend и native upgrade. Schema/DTO compatibility не заменяет эти сценарии. |
| **P21 — сквозная приёмка** | Причинные regressions, MySQL suites/migrations, собранные browser fixtures, часть native storage tests, benchmark budgets, image smokes, release-lineage и always-running CI gates реализованы. | **2/4:** полный auth/native сценарий; **5:** agreed SLO и representative saturation; **6:** required checks фактически не включены; **7:** full recovery/alert/rollback rehearsal и observer deploy; **8:** нет полного F→PR→evidence→migration→rollout→owner→status реестра. |

## Подтверждённые пропуски реализации

### 1. P19.6: поздняя запись в мобильной карточке заказа

[Leave](../mobile/src/app/features/order-details.page.ts#L2443) инвалидирует route ticket;
[enter](../mobile/src/app/features/order-details.page.ts#L2451) через
`activateOrderRoute` на строке 2532 делает GET. При последовательности
**POST A → leave → return A → GET старого состояния → commit/ответ POST**
прежний ticket уже не принимается.

`runDetailsMutation` (4067), `runReviewMutation` (4099) и `runRecoveryBotMutation`
(4134) возвращаются без следующего GET. В
[OrderPaymentFacade](../mobile/src/app/features/order-details/order-payment.facade.ts#L96)
та же проверка стоит перед `reload()`. Экран может оставаться устаревшим до ручного
обновления. Нового runtime reproducer в этой сверке не запускали.

Для закрытия нужен GET после завершения записи прежнего посещения, если текущий
ресурс совпадает; без автоматического повторного POST, применения старого payload
и перезаписи нового draft. У manager соответствующий `PageWriteTracker` уже есть
и проверен runtime-регрессией.

### 2. P14.2/3/5/7: обход очереди и незавершённый receiver contract

Активный [LeadSenderController](../backend/src/main/java/com/hunt/otziv/l_lead/controller/LeadSenderController.java#L23)
обрабатывает `POST /api/leads/sendToServer` через
[LeadTransferServiceImpl.sendLeadToServer](../backend/src/main/java/com/hunt/otziv/l_lead/service/LeadTransferServiceImpl.java#L40).
На строке 51 выполняется `restTemplate.postForEntity` непосредственно в response path.
Этот вход не сохраняет durable intent/operation ID нового queue-пути.

Новый worker передаёт `Idempotency-Key`, но локальный
[LeadSyncController](../backend/src/main/java/com/hunt/otziv/l_lead/controller/LeadSyncController.java#L74)
в `/sync` и `/update` применяет DTO через mapper/repository без persistent receipt
и проверки entity version. `payload_version` очереди — версия JSON-схемы.
FIFO доступных committed команд не доказывает защиту получателя от старого
обновления после нового или обгона ещё незакоммиченного producer.

Нужно устранить прямой send-owner и завершить согласованный контракт версии,
дедупликации и подтверждения с проверкой принимающего приложения. Текущий
[runbook](ARCHITECTURE_ROLLOUT.md#L27) честно признаёт receiver-proof незавершённым.
Факт случившегося дублирования или отката production-данных этой сверкой не установлен.

### 3. P05.3/4 и P15.6: автоматический restart WhatsApp

[scheduleRestart](../whatsapp/index.js#L592) ожидает `client.destroy()` без deadline.
При rejection он пишет предупреждение и затем вызывает `startClient()`.
Остановка admission и bounded `taskLimiter.drain()` реализованы в `shutdown`
(1354–1378), но не в этом пути автоматической замены клиента.

Следовательно, перед созданием нового клиента не доказано завершение старого
browser/session, а зависшее уничтожение не имеет ограниченного исхода. Нужны
единый restart lifecycle и причинные failure/hang tests. Это не утверждение,
что конкретная параллельная WhatsApp-сессия уже наблюдалась, и не опровержение
исправленного владения permit обычной отправки.

### 4. P08.5 и P21.7: Docker observer отсутствует в штатном deploy

[deploy-prod.ps1](../infrastructure/scripts/prod/deploy-prod.ps1#L3001) запускает
`dozzle alloy` с `--no-deps`. Единственное упоминание `docker-observer` в скрипте
(1007) добавляет каталог в отправляемый набор; build/up/recreate proxy отсутствует.
При этом [Dozzle](../docker-compose.yaml#L865) и
[Alloy](../infrastructure/alloy/config.alloy#L7) уже используют `docker-observer:2375`.

Если proxy ещё не поднят отдельно, первый такой deployment не создаст его;
последующие не обновят его этим скриптом. Возможна потеря контейнерного
discovery/log flow. Текущий deploy contract проверяет указанную `--no-deps`
команду и не обнаруживает отсутствующий старт proxy. Нужны его управляемый
build/start/readiness и последовательная проверка consumers в штатном сценарии.

## Незавершённые протоколы, инструменты и доказательства

- **P11.2–4:** [KeycloakSessionAuthority](../backend/src/main/java/com/hunt/otziv/u_users/service/KeycloakSessionAuthority.java#L61)
  читает текущие роли. [Runbook](AUTH_EPOCH_PUSH_REVOKE_ROLLOUT.md#L119) прямо
  ограничивает гарантию: внешние `enabled/roles` туда и обратно не восстанавливают
  пропущенную историю security mutations; нужен issuer event protocol, если эти
  пути должны давать отзыв сессий. План перечислял внешние admin/account/reset,
  роли и активность. Покрытие всех этих путей и их fence ещё не доказано.
  Это ограничение контракта, не заявление об успешно воспроизведённом обходе входа.
- **P07.5–9:** recovery manifest проверяет operator attestation общего write fence,
  но сам fence не устанавливает. Компаньоны MySQL/objects/secrets в CLI-доказательстве
  были synthetic и не восстановлены одним стендом. Session invalidation/signing
  recovery policy названа, но builder/drill её не исполняют. Нужен комплексный
  стенд с фактическим восстановлением компонентов и проверками приложения;
  database-only PASS его не заменяет.
- **P12.1 и перечень проверок:** рабочая таблица legal assignment/offer transitions
  и counter effects не найдена. В
  [MySQL concurrency suite](../backend/src/test/java/com/hunt/otziv/performers/service/PerformerAssignmentConcurrencyMySqlIntegrationTest.java#L127)
  есть double accept, два expire scheduler и accept/expire; отдельное управляемое
  interleaving accept/decline не найдено. Это недостающая проверка, не найденная
  ошибка уже реализованных блокировок.
- **P21.8:** таблица исходного плана связывает F с P и приёмкой; она не содержит
  полную фактическую связь каждого F01–F20 с PR, regression, migration/backfill,
  результатом rollout, владельцем и этапом закрытия. Настоящая матрица проверяет
  выполнение пакетов и не подменяет такой release registry.

## Оставшаяся внешняя приёмка и конфигурация

1. **P21.6:** GET-only проверка `Claidd/otziv_o`, `main`, revision
   `3458e56f29ec0288925f2a4795105fe863da5158` вернула `protected:false`,
   rules HTTP 200 с `[]`, `UNPROTECTED` и отсутствие required checks.
   Все **18** подготовленных проверок не обязательны; **10** новых ещё не имеют
   опубликованного успешного результата на этом SHA. Это подтверждённое состояние,
   а не вывод только из недоступности подробного protection endpoint.
2. **P00/P20/P21:** последний настоящий опубликованный Android73 проверен по
   signature/hash и browser-сценарию его неизменённых assets. Минимальная версия
   в [inventory](../contracts/releases/inventory.json) остаётся `null`;
   `minSupportedVersionCode=0` не идентифицирует проверенный старый клиент.
   Нужны fixtures поддерживаемого минимума, новый клиент с выбранным rollback
   backend, production-signed upgrade и полная auth/background/resume/push matrix.
3. **P06:** последние сохранённые image scans содержат vendor-unfixed
   HIGH/CRITICAL: worker **84/7**, WhatsApp **98/7**, publisher **52/4**.
   Доступных исправлений, блокирующих эти сохранённые scans, не было;
   `unresolvedRiskReview=REQUIRED`, принятие риска и срок не заполнены.
   Это не основание называть образы свободными от уязвимостей или выпуск разрешённым.
   Нужен документированный review и актуальная проверка точных release artifacts,
   включая не охваченные application matrix компоненты production.
4. **P07/P09:** реальные независимые recovery/monitoring источники, владельцы,
   получатель alerts, RPO/RTO/SLO и schedule не согласованы/не подтверждены.
   Нужны полный restore drill, реальная доставка тестового сигнала и отказы
   backend/целого host/backup destination/канала оповещения.
5. **P04/P11/P12/P13/P14/P15:** перед включением остаются целевой preflight,
   решения по старой истории, остановка старых writers/consumers, bootstrap,
   совместимый cutover, reconciliation UNKNOWN и наблюдение. Нулевая очередь
   свежей копии и выключенные локальные отправки не подтверждают эти действия.
6. **P18/P21.5/7:** performance evidence содержит SQL/EXPLAIN и bounded throughput
   на synthetic fixture; согласованных product SLO и точки production saturation
   нет. Для выпуска остаётся также rehearsal отката соответствующего набора
   image/schema/SDK/APK, с сохранением внешних receipts и security state.

## Что действительно подтверждено тестами

| Сохранённая проверка | Проверенный результат и граница вывода |
|---|---|
| Полный backend `clean verify` | **4683 tests, 0 failures, 0 errors, 15 прежних skipped**; Java 26, настоящий MySQL, 674 XML. Завершён 12:51:11 UTC. Хеш журнала и итоги повторно сверены; это не новый запуск. |
| Поздние contract metadata изменения | Отдельные source hashes и follow-up evidence совпадают; 7 Java / 26 generated-shared tests, воспроизводимая генерация. Они не выдаются за часть более раннего полного suite. |
| Web/mobile | Сохранённые web **697**, mobile **148 runtime + 229 unit**, production builds и **22 Chromium** cases успешны. Это не полный production/native login или весь support window. |
| Архитектура | **12/12**: boundaries, причинные compiled negative fixtures, exhaustive encapsulation. Existing debt явно зафиксирован. |
| Старые отправки/commands | P15 scoped **225 PASS**, общие MVC/API status/publication regression suites и Spring/MySQL fences. Пересекающиеся наборы не складываются в общий итог. |
| Локальное развёртывание | Штатный свежий VPS dump `prod-20260907-200907.sql.gz`, maintenance COMPLETE, schema **1.10.305**, **0 failed migrations**, app smoke PASS. Это прежний локальный запуск; production не развёрнут. |
| Измерения | **936 after observations**, 9 before/after строк, rollback 0. SQL **126→126 / 44→44 / 56→56**, 21 SELECT + 2 CTE plans. Сохранённые hashes не изменились; это измерение стоимости, а не устранение всех N+1 или обещание ускорения. |
| Runtime/security | Реальные worker/WA/observer/publisher image smokes; HTTPS publisher 6 cases PASS. Gitleaks directory scan — 0 findings; это не scan всей Git history. |

15 skipped включают 13 ранее явно отключённых AI fallback tests, отдельную
V217 upgrade fixture и opt-in Keycloak runtime test. Последний дополнительно
проверялся отдельным настоящим issuer-прогоном; успешный общий suite не означает,
что его opt-in случай запускался внутри того же Maven вызова.

Основные локальные доказательства:

- [Новая проверка хешей и XML](../.codex-tmp/plan-recheck-20260907/evidence-verification.json)
  и [повторное наблюдение GitHub](../.codex-tmp/plan-recheck-20260907/github-policy-observation.json).
- [Полный backend результат](../.codex-tmp/remediation-completion-20260907/final-integration-result.json),
  [source attestation](../.codex-tmp/remediation-completion-20260907/final-source-attestation.json)
  и [локальная схема/image](../.codex-tmp/remediation-completion-20260907/local-final-runtime.json).
- [Recovery CLI](../.codex-tmp/recovery-cli-e2e-20260907/summary.json),
  [performance](FINANCIAL_PERFORMANCE_EVIDENCE_2026-09-07.md),
  [native evidence](NATIVE_ANDROID_VERIFICATION_2026-09-07.md),
  [контракт и поддерживаемые релизы](../contracts/README.md).

Хеши относятся к срезу **до добавления этого отчёта и ссылок на него**. При сверке
изменены только документы аудита и локальные evidence-файлы; исходники приложения,
тесты, конфигурация запуска и БД не изменялись. Локальные evidence не являются
опубликованными release attestations.

Не считаются дополнительными невыполненными требованиями: удаление вообще всех
legacy API-методов, нулевой архитектурный baseline, полный запрет любых больших
facades и durable outbox для каждого publication callback. Такие более сильные
цели не подменяют конкретные критерии исходного P15/P16/P18/P19.
