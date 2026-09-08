# Доработка архитектурного плана: реализация и доказательства

Текущее продолжение: [C12 — Alloy и проверенный rollback MySQL](C12_RUNTIME_REMEDIATION_2026-09-08.md). Для точного опубликованного Alloy подтверждено отсутствие затронутого daemon-кода; полный скан, 13 проверок сохранения позиций и полный Docker/file consumer smoke прошли. Добавлен режим штатного MySQL rehearsal: 33 unit-теста и 49 проверок переноса/восстановления прошли. Образы БД по умолчанию сохранены; Alpine PostgreSQL отклонён по фактическому изменению сортировки и регистра. Кандидат phpMyAdmin прошёл 99 runtime-проверок и полный скан с 0 HIGH/CRITICAL; 273 независимые проверки подтвердили источники и результаты. Добавлен отдельный набор публикации только для PMA; его публикация и активация ещё не подтверждены. Полный план и приёмка выпуска остаются открытыми.

Ниже сохранены исторические срезы C11 и предыдущих этапов; результаты относятся к указанным версиям и времени.

Текущее продолжение: [C11 — подготовка Maven-аудита и обязательные проверки ветки](C11_CI_PREFLIGHT_AND_BRANCH_POLICY_2026-09-08.md). Исправлено добавление неявных аргументов Maven в Actions; 11 проверок подготовки и 51 проверка политики и связанных инструментов прошли. Защита `main` с 20 обязательными проверками фактически включена и независимо подтверждена. Приёмка коммита остаётся заблокированной двумя аудитами; полный план открыт.

Ниже сохранены исторические срезы C10 и предыдущих этапов; их статусы относятся к указанным коммитам и времени.

Текущее продолжение: [C10 — обновление GitHub Actions до Node 24](C10_ACTION_RUNTIME_2026-09-08.md). Проверены четыре workflow и 40 тестов. Gitleaks на C9 прошёл для PR и push; полный план остаётся открытым из-за ошибок аудитов и условий выпуска. Результаты C9 не заменяют проверку нового коммита.

Текущее уточнение: [C9 — публичные контрольные суммы в проверке истории
Git](C9_SECRET_SCAN_METADATA_2026-09-08.md). Локально подтверждена точечная
коррекция ложных срабатываний; результаты CI C8 и оставшиеся ошибки
зафиксированы отдельно. Полная приёмка плана остаётся открытой.

Актуальное продолжение: [C8 — проверенные ссылки инфраструктуры и итоговые
результаты C7](C8_IMAGE_ACTIVATION_2026-09-08.md). Восемь компонентов используют
проверенные опубликованные образы; новый штатный локальный прогон со свежей БД
и обновлённым Keycloak прошёл. CI C7 имеет 18 успешных и два неуспешных gate
из ожидаемых 20; полный план и выпуск остаются открытыми. Ниже сохранена
история предыдущих этапов с относящимися к ним коммитами и результатами.

Этот отчёт относится к последнему поручению завершить остаток после
[повторной сверки](ARCHITECTURE_PLAN_RECHECK_2026-09-07.md). Исходный
[план P00–P21](ARCHITECTURE_REMEDIATION_PLAN_2026-09-07.md) и прежние результаты
сохранены. Полный backend-прогон и заключительные локальные проверки завершены;
числа ниже относятся только к указанным прогонам. Доработка оставшихся зависимостей
и приёмка выпуска остаются открытыми.

Изменения внесены в существующее рабочее дерево с сохранением изменений
пользователя. Производственное развёртывание, включение внешних отправок,
публикация APK не выполнялись. GitHub branch protection включён и подтверждён на этапе C11.

Проверенный снимок опубликован коммитом `25579270c5494eedb6f3badc0ad3737b041ba9d6`
в ветке `codex/architecture-remediation-20260907` и [draft PR №2](https://github.com/Claidd/otziv_o/pull/2).
Все записи F01–F20 связаны с этим PR; успешная приёмка CI и выпуска ещё не подтверждена.
Публикационный индекс содержит только проверенные версии backend: поздние параллельные
WorkerRisk-изменения и посторонняя диагностика сохранены отдельно в исходном рабочем дереве.

## Проверка всех пакетов

| Пакет | Выполненная доработка и проверка | Остаток приёмки |
|---|---|---|
| P00 | Фактический VPS: 13 контейнеров, 12 уникальных image config, один backend, две WhatsApp-реплики; хеш опубликованного JAR. Из него извлечены 68 `@Scheduled` методов в 58 классах, отдельно учтён динамический backup scheduler. Java 26/Maven 3.9.15 и полные снимки исходников. | Доступ к runtime scheduler endpoint вернул 401: фактическую регистрацию всех задач нельзя считать наблюдённой. Продуктовая нижняя граница поддерживаемых Android-версий не задана. |
| P01 | Защита manager board/editor, сброс loading при уходе, чтение при возвращении, дополнительная сверка после поздней записи. Ранее исправленная доска сохранена. | Подписанное native-обновление и приёмка выпуска. |
| P02 | Сохранены временный/окончательный исход refresh, durable storage, generation/logout fence, ограниченный безопасный retry и optional/public auth. Проверены архивные клиентские артефакты. | Настоящие native login/refresh/resume и обновление приложения с исходной подписью. |
| P03 | В V306 добавлены постоянный origin UUID и монотонная версия сущности; codec фиксирует неизменяемую команду, legacy checksum остаётся совместимым. | Согласованный выпуск отправителя и получателя. |
| P04 | Полный high-water inventory, cursor/checkpoint, причины/IDs, точные scanned/changed/conflict counts; FIFO, bounded claim, CAS и UNKNOWN fence. Штатная свежая копия VPS проверена и обслужена. | Production cutover/drain с новым наблюдением очереди непосредственно перед включением. Нулевые исторические очереди не выдаются за проверку непустых пачек. |
| P05 | Разрешение удерживается до фактического окончания задачи/cleanup. WhatsApp restart ограничен временем, новый client допускается только после подтверждённого завершения предыдущего. Durable flock/journal восстанавливает CLEAN/DIRTY; сбой требует сверки. | Приёмка настоящей WhatsApp-сессии и наблюдение после выпуска. |
| P06 | Полные npm audits, фактический fleet scan/SBOM, обновление BOM и уязвимых runtime-библиотек, отдельные образы Keycloak/MySQL/PostgreSQL. Проверены реальные ограничения сканера и точечные ошибочные соответствия чужим продуктам. Sonatype Free подключён; C2/C3 выполнили полный авторизованный анализ. | Открытые замечания инструментов сборки/тестов и отдельных upstream-образов; прохождение security gate после исправлений; решения по vendor-unfixed findings и замене неподдерживаемого MinIO. См. отдельные security reports. |
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
| P21 | Реальные runtime/MySQL/architecture/browser/recovery/image проверки и release-lineage gate в CI; F01–F20 имеют отдельные записи с migrations/evidence/remaining acceptance. Закрытие без PR, владельца и выпуска запрещено verifier. Проверенный снимок опубликован в [draft PR №2](https://github.com/Claidd/otziv_o/pull/2); F01–F20 связаны с реальным PR. Штатные pre-commit и pre-push hooks прошли. | Branch protection включён и подтверждён в C11. Остаются успешные checks на актуальном commit; native/DR/alert/production acceptance. Создание PR не подтверждает успешный CI. |

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
| Проверка секретов | Первый полный monitoring срез: Gitleaks PASS, 146,29 MB, 0 находок; `final-secret-scan-v3.log`. Дополнительно фактический pre-commit перед публикацией: 18 072 137 bytes, 0 находок, `publication/publication-commit-longpaths.log`; 275 изменённых Java-файлов прошли SQL guard, large-blob gate PASS. Конфигурация Gitleaks и allowlist не расширялись. Предыдущие находки были тестовыми литералами/текстом отчёта и исправлены в исходниках. |
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
`push --dry-run origin` первоначально подтвердила доступ. Затем обычный
`git push --set-upstream origin codex/architecture-remediation-20260907`
успешно опубликовал коммит `25579270…` с включённым pre-push hook; создан
[draft PR №2](https://github.com/Claidd/otziv_o/pull/2). Первые workflow запущены, их завершение
ещё проверяется. Привязку обязательных checks к реальному GitHub Actions app
нельзя подменить списком названий. GET-only verifier отклоняет дублирующиеся/неоднозначные результаты,
неверный commit, незавершённые или старше семи дней checks; правила rulesets
помечает отдельно как не проверенные, а не приравнивает к classic protection.

Пользователь самостоятельно завершил регистрацию Sonatype и выбрал бесплатное
использование. В интерфейсе подтверждён тариф Free. Это дополнительный источник
сведений для OWASP Dependency-Check, а не требование архитектурного стандарта.
Пользователь создал токен `otziv-dependency-audit`, активный до 8 октября 2026 года.
С его подтверждения он сохранён в repository secret `SONATYPE_GUIDE_TOKEN`:
GitHub показал `Repository secret added.`. Значение не выводилось в чат и не
записывалось в файлы проекта. Авторизованные C2/C3 прошли все стадии анализа
и завершились FAIL по найденным уязвимостям, а не из-за доступа. C3 использовал
кэш C2. На странице Free подтверждены 60,9 из 500 кредитов и 609 запросов;
это суммарное использование аккаунта. Платная подписка не оформлялась.

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

## Устранение ошибок первого hosted CI

[Первый PR workflow](https://github.com/Claidd/otziv_o/actions/runs/34154787670)
и push workflow на `25579270…` обнаружили отличия Linux runner от локальной
Windows-проверки. Все пять npm-аудитов первого PR прошли. Maven-попытка
`34154787682` отменена новым коммитом до запуска анализатора: это не результат Sonatype.

| Причина | Исправление и проверка |
| --- | --- |
| Trivy не мог записать `/scratch`: root без DAC_OVERRIDE не владеет bind-каталогом runner. | Scanner и converter на POSIX запускаются с UID/GID владельца временных каталогов; возможности контейнера не расширены. Настоящий Linux volume: исходный отказ воспроизведён, запись scratch/report/SBOM-путей новым пользователем прошла, 3 проверки; 2 unit-теста дополнительно прошли на Linux. Это проверка прав, полный hosted scan должен завершиться отдельно. |
| Новый push передавал нулевой SHA; Git-проверка оставляла exit 1 и пропускала сравнение миграций. | Все три range gate используют проверенный commit основной ветки из GitHub metadata. Неизвестная база прекращает проверку. 16 причинных Git-проверок PASS, реальные Flyway/large-blob проверки отклоняют нарушения; проверенный publication snapshot прошёл hygiene и 387 append-only миграций с нулевым входным SHA. |
| `sdkmanager` отсутствует в PATH Ubuntu runner. | Используется точный путь внутри установленного ANDROID_HOME/SDK_ROOT. 6 проверок Bash-адаптера PASS; настоящую установку SDK подтверждает следующий hosted job. |
| Capacitor mocks переходили между файлами тестов при повторном использовании Linux workers. | Включён `test.isolate=true`; код приложения и ожидания тестов сохранены. Причинный Linux прогон: 28 FAIL → 175/175 PASS; Windows после изменения: 175/175 PASS. |
| Worker-тест требовал удалённые SYS_ADMIN/SYS_CHROOT. | Проверяет текущий более строгий production contract: отдельный seccomp profile и отсутствие дополнительных capabilities/privileged/unconfined. 32/32 PASS. |
| AppArmor Ubuntu не разрешал user namespace скачанному Chromium. | CI helper создаёт профиль только для точного установленного headless-shell, проверяет настоящий renderer sandbox и удаляет собственный профиль через EXIT trap. 6 проверок границ/cleanup PASS; фактический Ubuntu preflight и 22 UI-сценария требуют нового hosted запуска. Общесистемные ограничения и Chromium sandbox не отключаются. |

Финальный Actionlint для обоих workflow PASS. Доказательства находятся в
`hosted-scanner-bind-proof.json`, `ci-base-revision-proof.json`,
`.codex-tmp/rc26/mobile-hosted-ci/result.json` и
`.codex-tmp/remediation-final-gaps-20260907/hosted-quality-ci/`.
Повторный CI и исходные security findings остаются открытыми до фактических результатов.

## Проверка после авторизованного аудита: 8 сентября

Полные отчёты C2/C3 и исходные ошибки сохранены. Sonatype обнаружил реальные
уязвимости Jackson и jsoup, которых не было в результате предыдущего image scan.
Runtime обновлён до Jackson 2.21.6/3.1.6 и jsoup 1.23.2; отдельно исправлены
семь зависимостей Maven-плагинов в их собственных class loaders.
[Авторизованный аудит](../infrastructure/runtime-security/MAVEN_HOSTED_AUDIT_2026-09-08.md)
содержит 44 поведенческие проверки плагинов, три настоящих Maven goals и 338
проверок точных исправлений ошибочных соответствий. Оставшиеся реальные находки
и ограничение Dependency-Check при чтении опубликованных POM не скрываются.

[Новый backend](../infrastructure/runtime-security/BACKEND_ADVISORY_VALIDATION_2026-09-08.json)
прошёл полный OS/JAR scan: 0 HIGH, 0 CRITICAL; остальные 145 находок сохранены.
Штатный `prod-like-smoke.ps1` прошёл с точным новым образом на уже восстановленной
и очищенной свежей копии VPS БД. Повторная загрузка не требовалась; stock MySQL
остался 9.0, 387 миграций применены, последняя 1.10.306, ошибочных нет.
Это отдельный результат от испытаний обновления hardened MySQL/PostgreSQL.

Контракт повторно экспортирован из проверенного снимка: 7 Java-проверок и
26 SDK-проверок PASS, 187 операций / 172 пути / 204 схемы. API-семантика сохранена;
изменился только fingerprint POM и один перевод строки в synthetic fixture.
Первая попытка полного runtime-прогона сохранила 4696 PASS, один отказ именно
этого fingerprint guard и 15 оговорённых skips. Исправленный контракт прошёл
повторную проверку. Новый полный `verify` окончательного POM завершён: **4712 тестов,
4697 PASS, 0 FAIL/ERROR и те же 15 оговорённых skips**, 678 XML-отчётов; все 3216
файлов снимка сохранили хеши. Пакет после этого прогона повторно сопоставлен с
просканированным образом: все 3517 main/resource entries, 330 вложенных JAR и 103
loader/support entries одинаковы; отличается только встроенный POM. Старый отказ
и аттестации не переписываются.

[Keycloak](../infrastructure/runtime-security/ISSUER_ORDERING_VALIDATION_2026-09-08.json)
получил точный downstream patch Quarkus: одна аннотация задаёт порядок регистрации
JPA-модели перед её снимком. Исходная гонка воспроизведена на настоящем scheduler;
все 73 тела методов и 132 остальных JAR entries сохранены побайтно. Новый образ
прошёл 20 protocol, 4 startup и 21 paired recovery проверку; полный OS/JAR scan
дал 0 HIGH/CRITICAL. Старый JWT после восстановления отклонён, старые refresh
отозваны, новый вход, расшифровка и точные версии объектов проверены.

[Завершённый C3 CI](../infrastructure/runtime-security/HOSTED_CI_REVIEW_2026-09-08.json)
подтвердил полный backend 4712/0 FAIL/15 skips, финансовый CTE, Android,
web/mobile/worker, шесть прикладных image scans и четыре monitoring candidates.
В обязательной двадцатке PR присутствуют все имена с настоящим App ID 15368:
17 SUCCESS и 3 FAIL. Нет отсутствующего required check из-за path filter.
Отказы — browser CDP probe и два security aggregate; issuer на PR прошёл, а на
push того же SHA воспроизвёл гонку. Browser probe дополнен `--enable-automation`
для чтения командной строки; sandbox не отключён. Локальная причинная проверка
и семь unit-проверок прошли. Оба исправления должны подтвердиться следующим CI.

14 старых pinned upstream images блокируются настоящими находками. Grafana
candidate прошёл 12 storage/rollback проверок, но raw scan сохраняет два HIGH
с ошибочным сопоставлением Tempo pseudoversion; исключения не добавлены.
Проверки выпуска и branch protection нельзя считать завершёнными до устранения
блокировок и подтверждённого server-side применения политики.

## Завершённый C4 CI и точная коррекция Kotlin

На commit `c990a5bf0988c9d367c70504f6c4f530e956e3ab` оба Quality workflow завершились:
22 SUCCESS и 16 FAIL в каждом. [Фактическая приёмка C4](../infrastructure/runtime-security/HOSTED_C4_ACCEPTANCE_2026-09-08.json)
содержит все 20 обязательных check names и реальные App IDs: **18 SUCCESS,
2 FAIL** — Dependency audit gate и Upstream image security gate. Это подтверждает
работу проверок, но не означает успешный выпуск или включённую защиту main.

Backend: 4712 тестов / 0 FAIL/ERROR / 15 skips, плюс отдельный CTE-тест;
22 web/mobile UI-сценария и настоящий Linux sandbox; issuer 20 protocol,
4 ordinary startup, 21 paired recovery и PostgreSQL upgrade 10 — PASS.
Очищены собственные временные стенды. Все шесть прикладных image checks прошли.
У самого backend и issuer 0 HIGH/CRITICAL. У PostgreSQL сохраняются 73 HIGH и
13 CRITICAL без FixedVersion: автоматический gate пропускает отсутствие
доступных исправлений, но явно требует отдельного risk review. Принятия риска нет.

Четыре monitoring candidates прошли. Grafana прошёл 12 storage/rollback проверок,
но два raw HIGH для Tempo pseudoversion остаются блокирующими. Ещё 14 старых
pinned upstream images завершились FAIL по настоящим scanner reports; технических
отказов сканера среди них нет.

[Авторизованный C4 Maven audit](../infrastructure/runtime-security/MAVEN_HOSTED_C4_AUDIT_2026-09-08.md)
завершён с кэшем: 490 dependency entries, 46 artifact/advisory pairs, из них
24 HIGH/CRITICAL. Jackson 2.21.6/3.1.6 и jsoup 1.23.2 находок не имеют.
Сохранены уязвимости Site Jetty и shaded Docker transport, а также расхождение
опубликованных plugin POM с уже исправленными фактическими execution realms.

Последнее дополнение меняет только две точные Kotlin package/version/CVE rules,
их доказательство и отчёты. В JAR jdk7/jdk8 есть только module-info.class;
перегруппировка Dependency-Check сделала их новым представителем прежней
ошибочной Kotlin/KAPT записи. Настоящий parser/suppression engine/bundler прошёл
129 проверок, в том числе сохранение неизвестных CVE, других версий и KAPT.
На неизменном отчёте контрольная модель даёт 45 pairs / 23 HIGH/CRITICAL;
это не выдаётся за последующий hosted rescan. POM, backend, клиенты, контракты
и workflow сохранены; результаты последнего автоматического аудита отражаются
в PR вместе с его точным commit, без переписывания исходных C4 evidence.

## C6: исправление сборочных зависимостей и подготовка публикации образов

Новый [сборочный reactor](../backend/build-support/README.md) устанавливается
перед backend в Dockerfile, CI и штатном локальном сценарии. Он содержит Site
с Jetty 12, transport для Testcontainers с исправленным встроенным HttpClient и
адаптер Dependency-Check, который учитывает фактические зависимости Maven-плагинов.
Адаптер сохраняет унаследованные параметры анализатора и ошибки предыдущих
стадий. Аудит охватывает backend, все четыре проекта reactor и отдельный модуль
Keycloak; находки одного проекта не отменяют проверку остальных. Пороги,
анализаторы и срок действия кэша сохранены.

[Проверка C6 Maven](C6_MAVEN_REMEDIATION_2026-09-08.md) содержит фактические
сборки и полный локальный граф из 484 JAR: 0 HIGH/CRITICAL, пять MEDIUM сохранены.
Полный backend verify завершился: 4712 тестов, 4697 прошли, 15 оговорённых
пропусков, 0 FAIL/ERROR. Все 3517 main/resource entries и 330 вложенных JAR
совпадают с предыдущим проверенным runtime. Новый образ отдельно просканирован:
0 HIGH/CRITICAL. [Штатное локальное развёртывание C6](../infrastructure/runtime-security/BACKEND_C6_RUNTIME_VALIDATION_2026-09-08.json)
прошло с новым скачиванием БД с VPS: целостность дампа подтверждена,
387 миграций успешны, 29 защитных настроек и восемь отключённых флагов отправки
проверены после запуска. Временный скачанный дамп удалён штатным restore.
Авторизованный hosted Sonatype для C6 пока не завершён;
локальный Trivy не заменяет этот обязательный результат.

[Манифест 12 кандидатов](../infrastructure/runtime-security/reviewed-images.json)
сохраняет старые неизменяемые ссылки для проверки обновления и отката.
Публикация по явному запуску Actions строит образ из проверенного Git-снимка,
сохраняет provenance и SBOM и сканирует именно опубликованный digest.
[Проверка доказательств](../infrastructure/runtime-security/PUBLICATION_EVIDENCE.md)
сверяет реальные байты OCI, содержимое аттестаций, commit и Dockerfile.
Публикация и доступность без авторизации должны подтвердиться фактически;
локальные image IDs не выдаются за опубликованные registry digests.

Для Grafana два ошибочных сопоставления Tempo проверяются по точному executable
SHA, полному Go build-info и исходным исправлениям. Raw findings, SBOM и triage
не изменяются; решения сохраняются отдельно. Любая другая находка продолжает
блокировать gate по прежним правилам. PostgreSQL, phpMyAdmin и Alloy сохраняют
находки без опубликованного исправления и требуют отдельной оценки риска.

Подзадача MinIO/mc отклонена автоматической проверкой из-за возможного риска
кибербезопасности; конкретная операция в отказе не указана. Она не повторялась
через другой инструмент. Эти два старых образа остаются блокировкой, а production
cutover, приёмка внешнего восстановления/мониторинга и включение защиты main
остаются открытыми. Ни один finding не закрывается только по локальным тестам.
