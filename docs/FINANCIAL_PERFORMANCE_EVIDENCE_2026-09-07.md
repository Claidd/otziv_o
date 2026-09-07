# Финансовые сценарии: before/after и EXPLAIN — 7 сентября 2026

Измерены реальные service/Hibernate/MySQL вызовы после выделения сценариев.
Сравнение подтверждает стоимость выбранных путей на синтетической fixture;
оно не доказывает production capacity и не устанавливает SLO.

## Условия

MySQL 9 pinned `8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383`;
1000 invoices, 10000 payment links, 20 managers и 50 текущих manager items.
Hikari pool 8, concurrency 1/4/8, 8 warmup на сценарий, затем 24/96/192 наблюдения.
Каждое наблюдение вызывает настоящий сценарий; rollback 0. SQL budgets 128/46/58
проверяются исполняемым CI. Артефакты содержат p50/p95/max, SQL time, commits,
число SQL и планы оптимизатора.

Основной harness во всех сравнительных запусках побайтно одинаков:
`50f9009d186b3ed062531d14bf3a348b099549fb4f91420dc3c6f1aae00e3984`.
After вызывается через companion: он задаёт те же 24 итерации и после завершения
неизменённого измерительного метода дополнительно объясняет WITH-запросы.
Дополнительный EXPLAIN не входит в измеряемый участок.

Before для двух финансовых сценариев взят из сохранённого среза перед финансовой
декомпозицией; для manager details — из отдельного сохранённого среза перед его
декомпозицией. Это разные этапы работы, а не заявление о проверке исходного
production до всего аудита. Манифесты этих снимков сохранены. Plan-only pilot
для before CTE не используется вместо полных before latency reports.

Общий host: Intel Core Ultra 9 185H, 16 cores / 22 logical, около 32 GB RAM;
Docker Linux 22 CPU / около 16 GB. Во время части before прогонов шли другие проверки;
локальные сервисы также используют host. Поэтому изменение latency ниже —
наблюдение, а не доказательство причинного ускорения или замедления рефакторингом.

## Результаты

| Сценарий | Concurrency | SQL до → после | p50 ms до → после | p95 ms до → после | Завершений/с до → после |
|---|---:|---:|---:|---:|---:|
| Общие счета (20 карточек) | 1 | 126 → 126 | 268.2 → 185.8 | 415.9 → 225.4 | 3.43 → 5.15 |
| Общие счета (20 карточек) | 4 | 126 → 126 | 245.8 → 166.6 | 324.5 → 264.3 | 16.06 → 21.95 |
| Общие счета (20 карточек) | 8 | 126 → 126 | 274.8 → 204.7 | 312.0 → 233.8 | 28.35 → 38.06 |
| Платежи (20 строк) | 1 | 44 → 44 | 492.3 → 444.2 | 561.6 → 496.9 | 1.99 → 2.23 |
| Платежи (20 строк) | 4 | 44 → 44 | 597.4 → 436.7 | 679.1 → 532.6 | 6.62 → 8.88 |
| Платежи (20 строк) | 8 | 44 → 44 | 632.8 → 675.1 | 720.1 → 753.7 | 12.39 → 11.75 |
| Manager details (50 карточек) | 1 | 56 → 56 | 79.7 → 75.9 | 118.9 → 138.3 | 11.89 → 11.77 |
| Manager details (50 карточек) | 4 | 56 → 56 | 86.5 → 85.9 | 110.3 → 104.8 | 44.17 → 45.39 |
| Manager details (50 карточек) | 8 | 56 → 56 | 101.4 → 92.6 | 115.8 → 131.1 | 78.25 → 79.93 |

## Планы и оставшаяся стоимость

Основной отчёт сохраняет 21 SELECT plan; дополнительно сохранены оба основных
WITH-плана invoice count и invoice ID page (27/29 реальных bind-setters).
Они получены из фактических JDBC вызовов на той же fixture. Основные CTE SQL
до/после совпали. Это EXPLAIN FORMAT=JSON — оценки, а не EXPLAIN ANALYZE.

Before CTE materializes board_rows во temporary table; invoice scan оценивается
примерно в 1000 строк, account использует PK/eq_ref, ID page — filesort. Внутри
materialized query присутствуют dependent/noncacheable select-list subqueries.
Верхний query cost не включает всю вложенную стоимость; estimate не доказывает
фактическое число loops, поскольку часть CASE/OR может short-circuit.

Payment admin выполняет count, summary и широкую страницу на всех 10000 links;
page использует temporary/filesort. ALL scan при нефильтрованной выборке всей
fixture сам по себе не доказывает отсутствие нужного индекса.

В общих счетах остаются per-card обращения к amount/bad-review summaries;
в manager details — отдельный indexed lookup concrete examples на каждый item.
Индекс не устраняет round trips. Поэтому эти результаты не объявляются
устранением всех N+1. Изменение плана следующих оптимизаций требует отдельной
репрезентативной fixture и проверки поведения, а не механического изменения SQL.

Fixture однородна: один order на invoice, пустые concrete examples/история,
нет длинных successor chains и конкуренции денежных provider mutations.
Каталог SQL сохраняет первый набор параметров без частоты каждого SQL;
он не устанавливает точную долю стоимости N+1. Concurrency 1/4/8 даёт ограниченную
кривую throughput, а не доказанную точку production saturation.

## Воспроизведение и доказательства

Команды: [README benchmark](../backend/src/test/java/com/hunt/otziv/performance/README.md)
и [сравнение отчётов](../infrastructure/scripts/performance/README.md).
Evidence: `.codex-tmp/remediation-completion-20260907/` —
`performance-before-finance.json`, `performance-before-manager.json`,
`performance-after.json`, `performance-comparison/comparison.json`,
`cte-before-finance-plans.json`, `cte-after-plans.json`,
`performance-before-source-manifests.json`, `cte-current-ready-manifest.json`,
`performance-host-profile.json` и журналы запусков. Все 2446 production source files
after snapshot побайтно совпали с полным проверенным backend snapshot.
