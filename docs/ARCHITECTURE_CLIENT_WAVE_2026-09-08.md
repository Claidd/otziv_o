# Клиентская архитектурная волна A09/A12 — 2026-09-08

Эта волна завершает согласованный public-payments scope A12 и сценарий payment link в OrderDetails по A09. Она не закрывает весь A09/F07 или весь A12. Backend wire DTO, права, денежные расчёты и правила выбора получателя здесь не изменялись. Общую сборку и backend contract re-export координирует основной remediation run.

## Инвентаризация моделей

AST-инвентаризация находится в `.codex-tmp/architecture-audit-2026-09-08/a12-dto-inventory-before.json` и `a12-dto-inventory-after.json`; скрипт `client-dto-inventory.mjs` воспроизводит срез. В списке отдельно отмечены manual interfaces и local aliases/view enums. Имя, совпадающее с generated schema, — кандидат для проверки, а не доказательство семантического равенства. Представления экрана нельзя механически считать transport DTO.

| Файл / семейство | До: локальные declarations | До: manual interfaces | После этой волны: manual interfaces |
|---|---:|---:|---:|
| web `payments.api.ts` | 39 | 27 | 23 |
| web `common-billing.api.ts` | 16 | 15 | 15 |
| web `contractor-payments.api.ts` | 25 | 17 | 17 |
| web `manager.api.ts` (orders + manager) | 69 | 55 | 55 |
| web `manager-control.api.ts` | 31 | 26 | 26 |
| mobile compatibility `api.service.ts` | 279 | 237 | 233 |

Число mobile feature API файлов, импортирующих типы из compatibility ApiService: **24 → 23**. Устранено одно конкретное обратное ребро `PublicPaymentsApi → ApiService`. Остальные семьи остаются видимым долгом. Generated input/output уже охватывает публичные платежи; необходимость отдельного backend schema изменения для этой волны отсутствует.

## A12: public payments

Условное решение по специализированной AI-границе оформлено отдельно в [ADR-013](ADR-013-SPECIALIZED-AI-PROVIDER-BOUNDARY.md). Текущий adapter сохраняется с явными ограничениями и триггерами application port; это не закрывает обязательную миграцию оставшихся клиентских контрактов.

Удалены одинаковые ручные определения `PublicPaymentInitResponse`, `PublicSbpBank`, `PublicCommonInvoice`, `PublicCommonInvoiceOrder` из web PaymentsApi и mobile ApiService. Shared `public-payments.ts` выводит presentation projections из generated `PublicPaymentInitResponseOutput`, `PublicSbpBankResponseOutput`, `PublicCommonInvoiceResponseOutput`, `CommonInvoiceOrderResponseOutput`; копии wire field types не поддерживаются вручную. Компилятор проверяет имена и типы полей runtime narrowing по generated DTO.

Восемь операций в mobile PublicPaymentsApi и соответствующие web методы сохраняют HTTP method, token encoding, body, Angular interceptors, cancellation и исходные errors. Init body проверяется типом `PublicPaymentInitRequestInput`, HTTP response использует generated raw output, а presentation adapter проверяет необходимые экрану поля. Compatibility ApiService только re-export-ит shared типы и делегирует feature transport. Уже существующий shared PublicPaymentLink decoder сохранён и применяется также к ответу `manual-paid`.

Нулевые/отсутствующие legacy optional поля не получают выдуманных значений. Init допускает nullable URL/ID/status, включая QR-only ответы. Суммы не пересчитываются, kopecks/IDs проверяются как safe integers. Неизвестный invoice status/route сохраняется для отображения, но существующая общая политика выключает payment/report capabilities. Для данных, требуемых экрану (например, actionable bank ID или суммы), malformed payload вызывает явную ошибку адаптера. Нового synchronous импорта полного generated schema catalog в payment bundle нет: типовые импорты стираются, существующий interceptor продолжает lazy runtime validation.

Граница закреплена Node architecture test, общими fixture/parity cases и реальными Angular HttpClient tests обоих клиентов. DTO compatibility старого опубликованного Android 73 проверяется прежним штатным инструментом; это не подтверждает неизвестную минимально поддерживаемую версию или native upgrade.

## A09: OrderDetails payment link

`OrderPaymentLinkState` — отдельный экземпляр на экран. Он владеет status read, link cache, busy/error, route generation и жизненным циклом subscriptions. Component передаёт небольшой UI effects port для clipboard/toast и общего сообщения. Прежняя orchestration создания/повторного копирования удалена из component; template и остальные editor/report/publication сценарии сохраняются.

Protected status запрашивается один раз только для ADMIN/OWNER. Pending create подавляет второй create; готовая ссылка копируется из cache без нового POST. Смена маршрута сбрасывает payment state и инвалидирует поздние success/error, включая возврат на тот же order ID. Завершение платежа не очищает mutation key другого сценария. Destroy завершает subscriptions. Уже выпущенный write не повторяется автоматически. Clipboard получает проверку актуальности route: завершение старого async copy не выводит feedback на новом экране.

Эта граница проверяется отдельно от полного компонента; component integration подтверждает подключение к реальной смене ActivatedRoute, правам и существующему UI. Большие editor, publication, company report, web ManagerControl и mobile Manager остаются кандидатами следующих сценарных волн. Лимит строк не используется как критерий закрытия.

## Дополнительная проверка A08 sorting

Независимый review выявил оставшийся mobile local sort: кнопка меняла pageIndex, но не отправляла новый query, сортировала только загруженную страницу и могла принять pending page response. Mobile теперь отправляет `sortDirection`, использует общий journal cancellation channel, сбрасывает page в 0, очищает старые rows и сохраняет server order без локальной сортировки/мутации массива. Backend additive `sortDirection` с default desc и стабильным createdAt + id для LIVE/ARCHIVE реализуется отдельным владельцем backend. Web journal не имел sort control или локального sort и сохраняет default server order.

## Evidence и оставшаяся приёмка

- Shared installed-package fixture/parity suite: 38 PASS (`a12-shared-contract-tests.log`).
- Web public/billing boundary + public route scenarios: 20 PASS (`a12-web-tests.log`).
- Mobile public/billing boundary: 8 PASS (`a12-mobile-tests.log`).
- Architecture boundary: 5 PASS (`a12-architecture-tests.log`).
- A09 state + route integration: 15 PASS в `a09-web-payment-state-tests.log`.
- A08 mobile journal, включая pending-page sort race: 8 PASS (`a08-mobile-sort-tests.log`).
- Published Android 73 bidirectional DTO/observed request compatibility: PASS; minimum/native ограничения остаются (`a12-release-compatibility.log`).

На промежуточном срезе `generate.mjs --check` корректно отказал из-за изменённого backend `ApiManagerReviewController.java`: нужен production exporter после backend freeze, а не ручная смена hash (`a12-generation-check.log`). Эта волна не изменяла generated schema файлы. После backend export финальные generation и check прошли: 187 operations / 204 input-output schemas, включая sortDirection. Промежуточный отказ снят штатной генерацией; ручной смены hash не было.

Для локальных runtime tests shared package собран штатным `npm pack` и распакован в обе установленные копии; новые внешние зависимости и lockfile изменения не добавлялись. Docker/npm ci получают тот же tracked shared source при общей сборке.


Клиентская проверка после этого export, до дополнительной browser-приёмки редактора A08 ниже:

| Проверка | Результат |
|---|---|
| Web полный runtime suite | 108 файлов, 726 PASS |
| Mobile полный Node suite | 230 PASS |
| Mobile полный Angular runtime suite | 23 файла, 189 PASS |
| Shared installed-package contracts | 38 PASS |
| Web production build | PASS |
| Mobile production web assets build | PASS; native APK/AAB не собирался |
| Published Android 73 DTO compatibility | PASS; прежние minimum/native ограничения сохраняются |

Логи `client-final-*.log`, исходные hashes installed/source shared package и manifest обоих build trees сохранены в `.codex-tmp/architecture-audit-2026-09-08/client-final-artifact-evidence.json`. Для нового владельца DTO обновлены два старых теста: source assertion о ручном interface заменён проверкой сохранения routing fields реальным installed adapter; неполная lifecycle invoice fixture дополнена обязательными полями и типизирована shared моделью, чтобы проверять успешную обработку позднего ответа. Проверяемые lifecycle/cancellation требования сохранены.

Package drift check PASS: root dependencies обеих package.json совпадают с package-lock; lockfiles не менялись в этой волне (mtime 2026-09-07). Source shared package и все его src файлы побайтно совпадают с обеими установленными копиями. Результат и hashes: client-final-package-drift.json.

## Дополнительная browser-приёмка A08 и финальный client freeze

Реальные production bundles проверены новым `payment-journal.spec.mjs`. Сценарии смены фильтра во время pending page и search во время bootstrap прошли сразу. Сохранение задания A → открытие B → поздний ответ A воспроизвело дефект: обработчик A безусловно закрывал B editor, теряя видимость несохранённого текста. Старый прогон: 2 PASS / 1 FAIL; log `browser-journal-before.log`, trace, screenshot и build manifest сохранены в `.codex-tmp/correction-run-20260908/browser-journal-before-evidence/` до следующего запуска.

Это потребовало согласованного нарушения client source freeze: изменены только web/mobile обработчики manual-task editor и регрессионные тесты. Backend и контракты не менялись. Ответ связан с generation редактора, поэтому A→B и A→B→A не закрывают новую сессию и не показывают ей старую ошибку/success toast. У mobile дополнительно проверяется lifetime Ionic страницы; ответ до leave не меняет данные после resume. Уже выпущенная команда завершается без отмены или автоматического повтора. Переключение и редактирование B остаётся доступным; новый save ждёт завершения текущей task mutation, используя уже существующий общий busy marker, как task-status команды. Текущий editor сохраняет прежние success/error и явный retry.

Angular HttpClient регрессии проверяют успешный и ошибочный старый ответ, ABA, busy/repeated-click, текущий ответ, web destroy и mobile leave/resume. После исправления: web journal 12/12, mobile journal 16/16, полный web 108 файлов / **733 PASS**, полный mobile runtime 23 файла / **197 PASS**, связанный mobile Node safety suite 6/6. Обе production-сборки повторно прошли; native APK/AAB не собирался. Логи `a08-web-final-tests.log`, `a08-mobile-final-runtime.log`, `a08-mobile-node-tasks.log`, `a08-web-final-build.log`, `a08-mobile-final-build.log` находятся в `.codex-tmp/correction-run-20260908/`.

Отдельный `mobile-journal.spec.mjs` использует реальный mobile app login/PKCE/callback и синтетические сетевые ответы. Он проверяет pending page → server sort и latest status во время loading на Ionic production bundle; токены не внедряются через storage или Angular internals. Общие browser fixtures блокируют внешние origins/WebSockets и неизвестные mutation endpoints. Эти проверки не доказывают работу на native device или авторизацию реального backend.

Итоговый общий browser run после обеих новых сборок: **31/31 PASS** (20 web + 11 mobile-web), без skipped/retry. Включены 4 web journal/editor, 2 mobile journal и 3 настоящих Keycloak SDK refresh сценария. Лог: `.codex-tmp/correction-run-20260908/browser-journal.log`; JUnit, HTML, network attachments и manifest проверенных bundles сохранены в `browser-journal-final-evidence/` той же папки. Финальные source hashes и counts: `a08-client-final-evidence.json`. Это завершает локальную browser-приёмку этих сценариев A08; общий выпуск и оставшиеся A09/A12 отслеживаются отдельно.
