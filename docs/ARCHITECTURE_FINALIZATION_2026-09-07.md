# Доработка архитектурного плана: реализация и доказательства

Этот отчёт относится к последнему поручению завершить остаток после
[повторной сверки](ARCHITECTURE_PLAN_RECHECK_2026-09-07.md). Исходный
[план P00–P21](ARCHITECTURE_REMEDIATION_PLAN_2026-09-07.md) и прежние результаты
сохранены. Полный backend-прогон и заключительные локальные проверки завершены;
числа ниже относятся только к указанным прогонам. Доработка оставшихся зависимостей
и приёмка выпуска остаются открытыми.

Изменения внесены в существующее рабочее дерево с сохранением изменений
пользователя. Производственное развёртывание, включение внешних отправок,
публикация APK и изменение GitHub branch protection не выполнялись.

## Проверка всех пакетов

| Пакет | Выполненная доработка и проверка | Остаток приёмки |
|---|---|---|
| P00 | Фактический VPS: 13 контейнеров, 12 уникальных image config, один backend, две WhatsApp-реплики; хеш опубликованного JAR. Из него извлечены 68 `@Scheduled` методов в 58 классах, отдельно учтён динамический backup scheduler. Java 26/Maven 3.9.15 и полные снимки исходников. | Доступ к runtime scheduler endpoint вернул 401: фактическую регистрацию всех задач нельзя считать наблюдённой. Продуктовая нижняя граница поддерживаемых Android-версий не задана. |
| P01 | Защита manager board/editor, сброс loading при уходе, чтение при возвращении, дополнительная сверка после поздней записи. Ранее исправленная доска сохранена. | Подписанное native-обновление и приёмка выпуска. |
| P02 | Сохранены временный/окончательный исход refresh, durable storage, generation/logout fence, ограниченный безопасный retry и optional/public auth. Проверены архивные клиентские артефакты. | Настоящие native login/refresh/resume и обновление приложения с исходной подписью. |
| P03 | В V306 добавлены постоянный origin UUID и монотонная версия сущности; codec фиксирует неизменяемую команду, legacy checksum остаётся совместимым. | Согласованный выпуск отправителя и получателя. |
| P04 | Полный high-water inventory, cursor/checkpoint, причины/IDs, точные scanned/changed/conflict counts; FIFO, bounded claim, CAS и UNKNOWN fence. Штатная свежая копия VPS проверена и обслужена. | Production cutover/drain с новым наблюдением очереди непосредственно перед включением. Нулевые исторические очереди не выдаются за проверку непустых пачек. |
| P05 | Разрешение удерживается до фактического окончания задачи/cleanup. WhatsApp restart ограничен временем, новый client допускается только после подтверждённого завершения предыдущего. Durable flock/journal восстанавливает CLEAN/DIRTY; сбой требует сверки. | Приёмка настоящей WhatsApp-сессии и наблюдение после выпуска. |
| P06 | Полные npm audits, фактический fleet scan/SBOM, обновление BOM и уязвимых runtime-библиотек, отдельные образы Keycloak/MySQL/PostgreSQL. Проверены реальные ограничения сканера и точечные ошибочные соответствия чужим продуктам. Sonatype Free token сохранён в GitHub secret. | Открытые замечания инструментов сборки/тестов и отдельных upstream-образов; успешный полный авторизованный Sonatype-аудит; решения по vendor-unfixed findings и замене неподдерживаемого MinIO. См. отдельные security reports. |
| P07 | Реальное парное восстановление MySQL, PostgreSQL/Keycloak, точных версий объектов и секретов после уничтожения исходного стенда; старые access/refresh отклоняются, новый вход и расшифровка работают. Отдельно проверены обновления MySQL и PostgreSQL. | Независимый внешний storage, ответственный, согласованные RPO/RTO и rehearsal на производственном объёме. Локальный тест не доказывает независимость площадки. |
| P08 | Ограниченный Docker observer, штатный ordered rollout, реальные Dozzle SSE и Alloy → Loki записи. Ошибка readiness/consumer останавливает rollout. | Production rollout и наблюдение. |
| P09 | Running/cleanup/timeout/rejected, очереди и backup freshness собираются из реальных источников; HTTPS publisher и защищённый endpoint, явный NO_TRAFFIC, dedup/resolve/deadman. | Независимый host, выбранный получатель, доставка и подтверждение настоящего alert drill. |
| P10 | Backend учитывает readiness до claim. Настоящие Chromium/OCR и sandbox/cleanup probes проверены в собранных образах. | Проверка user namespaces/seccomp на целевом host и rollout именно проверенного digest. |
| P11 | Keycloak 26.7.3: транзакционный журнал поколения, неизменяемый session claim, изменения realm/user/role/group/client, точный отзыв online/offline SID, fail closed при недоступности/ошибке журнала. Provisioner сохраняет вложенные flows/OTP, сверяет полный конфиг и явно заданные clients. | Парный recovery point, bootstrap/shadow/drain смешанного парка и управляемое включение enforce. |
| P12 | Таблица разрешённых переходов и счётчиков; оба порядка commit accept/decline проверены на настоящем InnoDB. Сохранён единый порядок блокировок. | Production preflight, writer fence и миграция. |
| P13 | Управляемое батчевое обслуживание с сохранёнными исходными значениями, resume/abort/reconcile, точными счётчиками и проверкой fence. TTL начинается после подтверждённой доставки; неоднозначные исходы не повторяются. | Контролируемое включение доставки и наблюдение. |
| P14 | Обновление lead и команда атомарны; получатель хранит dedup receipt/high-water, отвергает другой body под тем же ключом и старую версию после новой. `/sendToServer` использует durable IMPORT; обходной прямой POST удалён. | Согласованный cutover receiver/sender и наблюдение реального контура. |
| P15 | Стабильные occurrences и ключи старых producers, frozen encrypted envelope, проверяемый receipt. Неизвестный результат не превращается в разрешение повторной отправки. Реальный Chromium/flock/crash lifecycle smoke. | Настоящая provider-сессия и выпуск обеих сторон протокола. |
| P16 | Проверяются foreign repositories, internal/public границы, новые циклы, generic/array зависимости; удалены неиспользуемые разрешения. Семь ADR описывают принятые решения. | Review/publish baseline и сохранённого архитектурного долга; существующие циклы не объявлены устранёнными. |
| P17 | Выбранные связанные MVC/API/worker-входы вызывают общие application commands; права, locks, audit и переходы имеют одного владельца. | Review и выпуск выбранных сценариев. |
| P18 | Финансовые/manager hotspots разделены по сценариям; сохранены public API, денежные транзакции и порядок locks. Before/after SQL, latency, throughput и EXPLAIN сопоставляются одним harness. | Согласованные продуктовые пороги производительности и наблюдение production. |
| P19 | 84 метода в 14 feature APIs; order-details использует PageWriteTracker для всех выбранных записей, включая payment/notes. Поздняя запись вызывает сверку того же ресурса, скрытый экран не читает его преждевременно. | Native приёмка. |
| P20 | Общий SDK 1.1.0: 187 операций, 172 пути, 204 input/output/error схемы из скомпилированных Spring/Jackson mappings. Сохранены auth/permission metadata, writes, pagination и compatibility fixtures. | Официальная нижняя поддерживаемая версия, подписанное обновление, опубликованный recovery target. |
| P21 | Реальные runtime/MySQL/architecture/browser/recovery/image проверки и release-lineage gate в CI; F01–F20 имеют отдельные записи с migrations/evidence/remaining acceptance. Закрытие без PR, владельца и выпуска запрещено verifier. Git Credential Manager настроен штатным device flow; push dry-run через `origin` прошёл с включённым pre-push hook. | Публикация успешных checks и фактическое включение branch protection; native/DR/alert/production acceptance. Dry-run не публикует ветку и не подтверждает успешный CI. |

## Доказательства завершённых проверок

Основной каталог этой волны: `.codex-tmp/finalization-20260907/`.
Предшествующие специализированные результаты сохранены в
`.codex-tmp/remediation-completion-20260907/` и
`.codex-tmp/remediation-final-gaps-20260907/`.

| Проверка | Результат |
|---|---|
| Полный backend с заключительным runtime BOM | 4712 тестов, 0 failures/errors, 15 известных skips: 4697 фактически исполнены и прошли. 678 XML reports; `final-clean-verify-v4.log`, `final-backend-attestation.json`. Предыдущий 4710-прогон сохранён отдельно. |
| Новый BOM и Telegram long polling | 42/42 PASS, настоящий HTTP update callback и MySQL fences; `maven-bom-targeted-v2.log`. |
| Контракты после окончательного POM | На POM `cef061f2…` один экспорт Java 7/7, генерация/check, shared parity 26/26 и DTO опубликованного Android 73 PASS; `final-tool-contract-supplement/java-contract-export.log`, `node-contract-checks.log`. 187 операций, 172 пути и 204 схемы; изменился только fingerprint POM в `x-source-sha256`, TypeScript и DTO fixtures побайтно сохранены. |
| Web | 697 тестов / 104 файла PASS; `frontend-full-tests.log`. |
| Mobile | 175 runtime / 21 файл + 229 unit PASS; `mobile-runtime-full.log`, `mobile-unit-full.log`. |
| Lead V306 | 61 targeted и 6 architecture PASS; immutable origin/version, stale/replay/conflict/rollback и прямой HTTP cutover. |
| Issuer protocol | 20/20 PASS на финальном Keycloak и новом PostgreSQL 17.11; `issuer-postgres1711-proof.json`. |
| MySQL upgrade | Upstream 33/33 PASS; hardened follow-up 43/43 PASS на том же томе, 320 таблиц и 1 048 758 строк в последней копии, точные history/checksums/JSON, app UP и graceful shutdown; `mysql-upgrade-hardened-v1/rehearsal.json`. |
| PostgreSQL upgrade/rollback | 10/10 PASS, исходные данные/права/locale сохранены, post-upgrade mutation отсутствует в отдельно восстановленной копии; `postgres-upgrade-v6/proof.json`. Финальная readiness отличает рабочий процесс от временного init-server. |
| Парное полное восстановление | 21/21 PASS на финальном BOM/JAR и новых MySQL/PostgreSQL/Keycloak; `full-system-hardened-v2/proof.json`, cleanup PASS. Java PID1 завершился за 1,585 секунды, закрыты оба запущенных пула. |
| UTF-8 subprocess regression | Старый код 1 FAIL/1 PASS, исправленный 2/2 PASS. Binary dump pipeline сохранён. |
| Infra/CI | 71 Node security/recovery/monitoring проверка, infrastructure contracts, release-lineage regression и actionlint PASS на указанном срезе. Первый широкий прогон выявил отсутствующий OpenSSL в PATH; повтор использовал уже установленный Git OpenSSL. |
| Штатный local smoke | Повторный `prod-like-smoke.ps1` PASS после нового BOM; `local-final-bom-smoke.log`, приложение healthy на `http://localhost:8088`. |
| Monitoring candidates | Prometheus 12, Loki 14, Alloy 13, Tempo 14 и Grafana 12 проверок сохранённых данных/cursors, restart и rollback PASS: всего 65. Реальные Docker/file streams PASS; у Grafana сохранены 11 569 файлов официальных UI/plugins/config/CA и проверена расшифровка секрета. В Tempo воспроизведено и исправлено зависание drain; исходный RED сохранён, очередь и 20 запусков регрессий с race detector прошли. Заключительный срез пяти кандидатов: 30 unit/contract checks, 12 SQL guard cases, Compose с пятью проверками отсутствующего digest и actionlint PASS. Проверены 53 ссылки на артефакты и 16 хешей исходников без расхождений. Точные образы и хеши: `infrastructure/runtime-security/MONITORING_CANDIDATES_2026-09-07.json`. |
| Проверка секретов | Финальный срез первой monitoring wave: Gitleaks PASS, 146,29 MB, 0 находок; `final-secret-scan-v3.log`. Конфигурация и allowlist не расширялись. Предыдущие находки были тестовыми литералами/текстом отчёта и исправлены в исходниках. |
| Свежий checkout и SQL guard | Воспроизведён CRLF-сбой трёх генераторов; узкие LF-правила для generated JSON/TypeScript прошли настоящие Windows/Linux checkout и Linux Node container. Два SQL004 замечания проверены как внутренние статические идентификаторы: разрешение ограничено точным путём, двумя строками и SHA всего файла из проверяемого index. 12 причинных проверок подтверждают отказ при изменении происхождения SQL, другого запроса, пути или правила; Java-код не менялся. |
| Required checks | GET-only наблюдение: main UNPROTECTED, 20 checks не закреплены; `final-branch-policy-observation-v2.json`. Удалённых изменений 0. |

Свежий stock restore получил `prod-20260907-225604.sql.gz`, 109 081 618 байт,
SHA-256 `fdab6b68faa78a6082ce09610273e88fa4a740b47f792d9009a9eccac1fbbce7`.
Checksum/sanitization выполнены; загруженный plaintext dump удалён штатным
скриптом. Managed maintenance восстановил исходные account/global fences;
итоговая схема приложения — V1.10.306. Повторный smoke использовал эту уже
проверенную свежую копию. Он не подменяет отдельную приёмку обновления движка БД.

Образ заключительного локального smoke:
`sha256:43e9b33cd152c4f567ea199a91777abd7165fd35349b841972407e5e95ce77e1`;
JAR SHA-256 `baf750d0616b2ebd4c680d9477934d81f85319b11992d18d0d24fa5e03b1339d`.
Полный suite завершился в отдельном неизменяемом снимке `finalization4`
в 16:15:46 UTC за 28 мин 44 сек. Все 3253 файла снимка совпали с манифестом;
1890 Java sources, 563 resources и 692 test files совпали с рабочим деревом.
Для проверенного среза после снимка обновлены инструменты audit/site и
изолированные зависимости Maven plugins в POM. Отдельное неизменяющее старую
аттестацию дополнение `final-backend-attestation-supplement-build-tools.json`
фиксирует POM `cef061f2…`, один финальный экспорт и 26 parity tests. Сравнение
нового JAR с пакетом полного suite и со сканированным runtime JAR подтвердило
побайтное совпадение всех 3517 class/resource entries и 330 вложенных библиотек,
включая классы Boot loader; различаются только три записи метаданных сборки.
Реальные Maven plugin realms, package/repackage, локальные install/deploy и
12 проверок Boot HTTP transport сохранены в отдельном
[манифесте инструментов](../infrastructure/runtime-security/MAVEN_BUILD_TOOL_PROOF_2026-09-07.json).
Полный suite повторно не запускался и не приписывается неизменному POM/JAR.

Перед этим экспортом 1890 Java sources, 563 resources и 692 test files побайтно
совпали с исходным снимком. Позже в рабочем дереве появились независимые
изменения WorkerRisk callback/service и тестов. Они сохранены, перечислены в
дополнении и исключены из этой аттестации и проверенного индекса публикации.
Последний сверялся отдельно: кроме нормализации UTF-8 CRLF/LF отличий от снимка
в этих трёх деревьях нет. Эти доказательства не подтверждают новый WorkerRisk
код, выбранный minimum supported release или native upgrade.
15 пропусков явно разобраны: 13 старых отключённых сценариев локальных draft,
один opt-in V217 migration clone и один opt-in local Keycloak test. Активные
planned/short fallback остаются и проверяются своими тестами.

## Существенные ограничения выпуска

Полный fleet scan действительно охватывает 13 production-контейнеров и
12 уникальных конфигураций образов. Старый опубликованный парк содержит
1001 HIGH/CRITICAL package finding: 804 с исправлениями и 197 без опубликованного
исправления, 347 разных advisory IDs. Эти числа относятся к старому парку;
их нельзя переносить на подготовленные новые образы или складывать с ними.
См. [реестр парка](../infrastructure/runtime-security/PUBLISHED_FLEET_2026-09-07.md).

Локальные кандидаты backend, Keycloak, MySQL, Prometheus, Loki, Tempo и Dozzle имеют
0 HIGH/CRITICAL. Alloy имеет 0 исправимых и 2 vendor-unfixed HIGH. PostgreSQL имеет
0 исправимых HIGH/CRITICAL и 86 vendor-unfixed строк, оставленных открытыми.
В Grafana исправлены четыре исходные OpenSSL/gRPC/Thrift находки. Остались
два raw HIGH по pseudo-version Tempo: история исходников подтверждает включение
обоих исправлений, но автоматический скан остаётся FAIL. Обоснование отдельной
проверки ложного соответствия приведено в [отчёте Grafana](../infrastructure/runtime-security/GRAFANA_DERIVATIVE_2026-09-07.md);
оно не подменяет результат автоматического gate.
Обновления мониторинга проверяются на реальных сохранённых данных и откате;
последний официальный тег сам по себе не означает отсутствие уязвимостей.
Ни временное исключение риска, ни назначенный владелец не выдуманы.

Полный Maven audit включает test/provided/system и плагины. Отдельно различены
реальные уязвимости и ошибочное сопоставление Java-артефактов другим продуктам.
Неполная проверка Sonatype без токена не считается PASS; CI прекращается до
сканирования при отсутствии обязательного секрета. Отключения анализаторов и
понижения порогов ради зелёного результата нет.

В браузере подтверждён вход владельца GitHub и доступ к Settings; classic branch
protection и rulesets отсутствуют. Git Credential Manager завершил штатный device
flow, учётные данные сохранены его обычным Windows-механизмом. Проверка
`push --dry-run origin` разрешила новую ветку с включённым pre-push hook;
удалённая ветка этой проверкой не создавалась. Успешные новые checks ещё не
опубликованы, их привязку к реальному GitHub Actions app нельзя подменить списком
названий. GET-only verifier отклоняет дублирующиеся/неоднозначные результаты,
неверный commit, незавершённые или старше семи дней checks; правила rulesets
помечает отдельно как не проверенные, а не приравнивает к classic protection.

Пользователь самостоятельно завершил регистрацию Sonatype и выбрал бесплатное
использование. В интерфейсе подтверждён тариф Free. Это дополнительный источник
сведений для OWASP Dependency-Check, а не требование архитектурного стандарта.
Пользователь создал токен `otziv-dependency-audit`, активный до 8 октября 2026 года.
С его подтверждения он сохранён в repository secret `SONATYPE_GUIDE_TOKEN`:
GitHub показал `Repository secret added.`. Значение не выводилось в чат и не
записывалось в файлы проекта. Полный авторизованный аудит ещё должен пройти
в CI. Платная подписка не оформлялась.

Не получены: исходный Android signing key, решение о минимальной поддерживаемой
версии, независимая площадка и
получатель сигналов с RPO/RTO и выбор поддерживаемого S3 после
прекращения сопровождения MinIO Community. Разрешение на работу не создаёт
эти учётные данные, площадку, лицензию или бизнес-решения.

Автоматическая проверка разрешений отдельно отклонила native Android/browser
действия, удаление старого rollback fixture и позднюю очистку защищённого
maintenance SQL backup с причиной `blocked by policy`.
Отклонённые действия не выполнялись и не повторялись через другой инструмент.
Старый rollback fixture остаётся перечисленным в своём cleanup-blocker evidence;
очистка новых успешно проверенных стендов имеет самостоятельные записи.
Защищённый `local-before-maintenance.sql` этой волны остался на месте:
`maintenance-cleanup-blocker.json` фиксирует отказ до выполнения команды.
Его нельзя путать с загруженным VPS gzip, удалённым штатным restore.

## Производительность и совместимость

[Финансовый benchmark](FINANCIAL_PERFORMANCE_EVIDENCE_2026-09-07.md) содержит
936 after observations, concurrency 1/4/8 и сопоставимые before/after запросы:
126→126, 44→44, 56→56 при бюджетах 128/46/58. Сохранены 21 SELECT и два CTE
EXPLAIN; это не EXPLAIN ANALYZE и не доказательство продуктового SLO.

[Queue saturation](../infrastructure/runtime-security/QUEUE_SATURATION.md)
проверяет шесть комбинаций реальных Spring TX/Hikari/MySQL при concurrency
1/4/8, 20 000 исторических и 1600 поступивших записей на сценарий.
UNKNOWN не захватывается повторно, late ack fenced, 1213 rollback retries
посчитаны отдельно. Результаты относятся к синтетическому стенду.

Проверены 21 фактический APK: debug 53 и release 54–73 с одинаковой подписью;
20 архивных release web assets прошли 60 browser сценариев. Android 54 — самая
старая проверенная версия, **не назначенный продуктовый минимум**. Текущие
web/mobile также проверены с отдельным совместимым recovery backend на новой
схеме. Старый опубликованный бинарник не допускается к V305/V306 посредством
возвращения удалённой legacy-таблицы. Подробности и границы native proof:
[CLIENT_RELEASE_COMPATIBILITY](CLIENT_RELEASE_COMPATIBILITY_2026-09-07.md).
