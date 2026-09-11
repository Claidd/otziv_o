# Закрытие замечаний повторной проверки — 7 сентября 2026

Последующая [сверка полного плана P00–P21](ARCHITECTURE_PLAN_RECHECK_2026-09-07.md)
подтвердила доказательства ниже, но выявила оставшиеся пропуски реализации
и приёмки. Этот отчёт фиксирует выполненные доработки, а не полное закрытие плана.

Этот отчёт относится к шести группам недоделок, перечисленным после проверки
[исходного плана P00–P21](ARCHITECTURE_REMEDIATION_PLAN_2026-09-07.md).
Он дополняет прежнюю проверку, не меняя критерии приёмки плана.
Изменения кода, документации и CI внесены; окончательные локальные проверки
фиксируются ниже. Production deployment и включение внешних отправок не выполнялись.

## Что изменено

| Пункты | Реализация и проверяемое поведение |
|---|---|
| **P01/P19** | Leave отменяет GET доски и сбрасывает loading; return перечитывает ту же cached page. Завершение прежней записи после возврата вызывает дополнительную объединённую сверку. Поздний результат не меняет новый редактор. 84 HTTP-метода выбранных областей переведены в 14 узких API; AST/type-checker подтвердил отсутствие их production-вызовов через общий ApiService. |
| **P04/P13** | Lead inventory/classification проходит весь фиксированный high-water диапазон через after/through cursor; возвращает IDs, причины, реальные scanned/changed/conflict counts и audit. Performer CLI сохраняет прогресс и оригинальные значения в той же транзакции, что и ограниченная пачка; поддерживает resume, pre-migration abort и отдельную reconciliation. Проверяет реальные блокировки writers/учётных записей и запрещает незавершённое либо пропущенное обслуживание до Flyway/JPA. Старые V288/V300 не изменены. |
| **P05/P09/P10** | Running/cleanup/timeout/rejected и возраст cleanup относятся к фактической работе; поддерживаемая browser/OCR работа получает сигнал отмены. Backend проверяет worker readiness до claim. Реальные runtime/queue/backup сигналы собираются вне scrape по ограниченному cache; при отсутствии трафика возвращается явное NO_TRAFFIC. Endpoint защищён отдельной stateless security chain; opt-in TLS publisher и контейнерный smoke добавлены в инфраструктуру. |
| **P15/P17** | Для старых producers введены сохранённые occurrences, encrypted frozen WhatsApp envelope и строгий receipt. AST нашёл 12 keyed бизнес-вызовов и ни одного unkeyed/null-key вызова. Telegram/MAX сохраняют PREPARED/UNKNOWN барьер до provider POST; timeout не разрешает повтор. Общие status/publication команды подключены к worker, manager API и связанным MVC-входам; права и канонические locks проверяются внутри сценария. |
| **P16/P20** | Gate проверяет все foreign-internal зависимости, public API и циклические рёбра, включая array/generic-only ссылки. 13 переносов рецензированы отдельно, 12 неиспользуемых разрешений удалены. Добавлены ADR по сессиям, деньгам, delivery, контрактам, выпуску и transport/application. Генерация из Spring signatures и реальной Jackson MVC модели охватывает 187 операций, 172 пути и 204 request/response/error схемы. SDK 1.1.0 используется обоими клиентами. |
| **P18/P21** | Добавлен воспроизводимый MySQL benchmark трёх реальных финансовых/manager query-сценариев: SQL, EXPLAIN, latency и throughput при concurrency 1/4/8. Сохранены before snapshots; сравнение отклоняет другой harness/fixture и неполные замеры. Benchmark/query budgets, release-lineage regression и compatibility verifier подключены к CI. Для required checks добавлен GET-only verifier и конкретная политика из 18 проверок; фактическое состояние GitHub проверено. |

В финансовом UX send/remind с lastError показывает предупреждение и operationId:
сохранённый статус INVOICED сам по себе больше не выдаётся за успешную доставку.
Смена платёжного маршрута/получателя блокируется до сверки незавершённого сообщения;
поздний finish не может подтвердить другую occurrence. Старые in-progress/UNKNOWN
и stale без идентификатора не получают новый ID даже через общий stale recovery.

## Доказательства

Финальный снимок содержит **3259 файлов backend/contracts/shared**;
SHA-256 manifest: `66e60b21053ddab59dca34732fb9a22ee6a6a9ba12d47c57657e28e3d9358816`.
Исходники пользователя и исходные результаты первой волны сохранены.

| Проверка | Результат |
|---|---|
| Полный backend clean verify | **4683 tests, 0 failures/errors, 15 ранее существовавших skipped**; Java 26 и настоящий MySQL, BUILD SUCCESS 12:51:11 UTC за 20:01. Итог совпал с 674 XML; 0 изменений проверенного source snapshot. |
| Архитектура | 12/12 PASS: 5 boundary, 6 causal policy, 1 exhaustive encapsulation. 3013 разрешённых legacy внутренних рёбер, 309 repository и 207 прежних циклических рёбер; новых repository/cycle edges нет. |
| Финансовые отправки | 271 CommonBillingService tests PASS после последней общей legacy-recovery защиты; отдельный Sender/occurrence/proxy MySQL набор проверяет persistent fence, повтор, race и rollback. |
| Старые producers и общие команды | Final P15 target 225/225 PASS, включая 15 реальных MySQL случаев. P17 регрессии 192 и causal target 99 — отдельные пересекающиеся прогоны, их числа не складываются. |
| Web / mobile | Web: 697 tests; mobile: 148 runtime + 229 unit. Обе production builds PASS. 22 Chromium сценария текущих клиентов PASS; построенные артефакты сверены по 196 hashes. |
| Контракты | 7 Java contract tests и 26 generated/shared parity tests PASS. Повторная генерация без drift. Реальная сериализация замкнута на 204 DTO-схемы. |
| Проверка секретов | Полный directory scan на прежнем pinned Gitleaks/config: 0 findings, без новых исключений. Метаданные SHA и случайные токены локального validation fixture проверены отдельно. |
| Выпущенные версии | Реальный опубликованный APK 73 проверен по update endpoint, SHA-256 и подписи; его неизменённые web assets прошли 3 browser сценария с новым DTO, UNKNOWN и последующим CONFIRMED без replay. Экспорт из фактического production JAR: 202→204 схемы, 171→172 пути, 0 breaking schema/operation changes. |
| Worker / monitoring | Scoped backend 69 PASS, worker 32, WhatsApp 89; отдельные runtime/collector/publisher проверки и действительный TLS container smoke PASS. Наборы пересекаются с другими прогонами. |
| CI / policy | Actionlint, infrastructure contracts и release-lineage regression PASS; causal scenario-comparison tests 3/3 PASS. GitHub verifier вернул UNPROTECTED: main действительно без защиты, требуемые 18 checks не enforced. |
| Производительность | PASS: 9 полных before/after строк при concurrency 1/4/8, 936 after наблюдений, rollback 0. SQL **126→126 / 44→44 / 56→56**; бюджеты 128/46/58 соблюдены. Сохранены 21 SELECT plan и два основных CTE плана до/после. [Числа, EXPLAIN и пределы сравнения](FINANCIAL_PERFORMANCE_EVIDENCE_2026-09-07.md). |
| Свежая БД и deployment smoke | PASS. Stock restore получил `prod-20260907-200907.sql.gz`: исходная схема 1.10.287, 374 успешные записи Flyway. Sanitization/checksums PASS, временный plaintext dump удалён. Managed CLI завершил полный цикл и восстановил fence; штатный smoke пересобрал app/web/worker/observer и прошёл на той же копии. Итог: схема 1.10.305, 386 Flyway rows, 0 failed; сервисы healthy на http://localhost:8088. |

После фиксации общего снимка изменён только формат fingerprint-метаданных контрактов:
поле с путями в качестве JSON-ключей заменено на список `{path, sha256}`. Все 411
текущих/архивных fingerprints сохранены; DTO, public contract и generated TypeScript
не изменились. Это устранило ложные срабатывания scanner без исключений. Отдельный
повторный Java contract target 7/7, parity 26/26, release compatibility и generation
check прошли; evidence находится в `contract-metadata-format/followup-evidence.json`.
Производственные Java исходники после общего freeze не менялись.
Дополнительный manual-only CTE companion вызывает тот же неизменённый benchmark,
затем строит планы WITH-запросов вне измеряемого участка. Он проверяется отдельным
прогоном; это не новый production-код и не дополнительный скрытый skipped test.

Fresh-copy maintenance run: `a2cfeec1-77db-4220-a1a2-6d8bbda1aea7`.
Все preflight/reconciliation потоки завершились; в этой копии **0 подходящих
исторических performer строк и 0 lead queue строк**. Поэтому реальный fresh-copy
прогон не выдаётся за нагрузочную проверку или перенос непустых пачек: эти случаи,
CAS conflicts и resume/abort покрыты отдельными MySQL fixtures. Приложение во время
обслуживания было остановлено; исходные account locks/global settings восстановлены,
временный DBA удалён. Новый образ был создан из штатной offline build функции,
проверен по image ID и оставался остановленным до конца обслуживания.
После полного stock smoke временный plaintext maintenance backup также удалён;
его SHA-256 и журнал проверенного завершения сохранены отдельно.
Работающий app image: `sha256:25180a62f620afd9a47fa8f03fab95bcd4f7bf8c2d37e0caaeb04d8d0538bec8`;
SHA-256 JAR: `5fd61bbc232d1e4545d35f1c3d805a6312f39a88eb0af8d8c12127cd86edf695`.
Все **2941 production class** работающего JAR побайтно совпали с JAR полного
проверенного backend snapshot; различий нет. V288 и V300 совпали с исходным
состоянием до этой доработки. Общий workspace manifest фиксирует 4561 исходный
файл, включая поздние test/metadata/CI/doc изменения; отдельные результаты их
проверки не выдаются за повторный полный backend suite.

Основные evidence indexes: `.codex-tmp/remediation-completion-20260907/`
`client-completion-evidence.json`, `clients-backend-completion-evidence.json`,
`final-source-manifest.json`, `p16-final-policy-match.json`,
`github-required-check-verification.json`, `final-source-attestation.json`,
`final-workspace-source-manifest.json`, `performance-final-summary.json`,
`final-security-scan-cte/summary.json` и журналы final/local прогонов.
Эти локальные evidence-файлы не являются опубликованными release attestations.

## Остаток внешней приёмки и пределы

- **GitHub required checks не включены.** Подготовленные workflows ещё не опубликованы
  как успешные checks на проверяемом head; доступного аутентифицированного GitHub
  admin подключения нет. Verifier фиксирует фактический отказ и не выдаёт draft
  policy за enforcement. После публикации проверок нужна установка и повторная
  read-only проверка конкретной политики; состав app/check IDs не выдумывается.
- **Минимальная поддерживаемая mobile версия не определена.** Текущий сервер
  публикует minSupportedVersionCode=0; latest 73 проверен, но это не доказывает
  совместимость всех более старых установок. Строгий supported-release gate
  намеренно остаётся красным без реального inventory/решения о минимуме.
- **Production rollout/drain, полный native upgrade/OIDC, независимый alert drill,
  полный DR/RPO/RTO и согласование SLO** остаются отдельными операционными действиями
  исходного плана. Локальные fixtures их не подменяют. Исправленные vulnerability
  blockers отсутствуют в проверенных новых worker/WhatsApp/publisher images,
  но vendor-unfixed HIGH/CRITICAL остаются и требуют отдельного решения владельца.
- Доска перечитывает состояние после наблюдаемого завершения транспорта. GET может
  опередить commit после оборванного соединения; это не durable receipt всех generic
  edit команд. В общем ApiService остаются 74 HTTP-метода других областей.
- Stable ID не превращает старый publication afterCommit callback в durable outbox:
  crash до callback способен потерять уведомление. Сохранены прежние sync/TX границы;
  неподтверждённая occurrence объединяет последующие поколения до receipt и не
  является очередью каждого события. Эти пределы явно отражены в ADR/runbook.

Подробности: [границы](ARCHITECTURE_BOUNDARY_REVIEW_2026-09-07.md),
[legacy queue maintenance](LEGACY_QUEUE_MAINTENANCE.md),
[message cutover](CLIENT_MESSAGE_IDENTITY_CUTOVER_2026-09-07.md),
[Telegram/MAX fence](CLIENT_MESSAGE_OPERATION_FENCE.md),
[общие команды](WORKER_COMMAND_BOUNDARIES.md), [решения](ADR-001-MODULE-BOUNDARIES.md).
