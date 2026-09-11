# P06: npm overrides и открытые контейнерные advisory

Это read-only сверка текущих manifests/locks, установленных версий и сохранённых runtime-отчётов. Новые image scans, установки и изменения зависимостей не выполнялись. Исключения сканера и решения о принятии риска не добавлены.

В 14:26:08 UTC выполнен свежий **полный** `npm audit --json` для каждого графа, включая dev dependencies:

| Граф | Всего dependencies по npm | Находки всех severity | Exit |
|---|---:|---:|---:|
| frontend | 575 | 0 | 0 |
| mobile | 668 | 0 | 0 |
| whatsapp | 227 | 0 | 0 |
| external-review-worker | 88 | 0 | 0 |

Это снимок базы npm на указанное время. Он не закрывает native OS CVE, не доказывает отсутствие неизвестных уязвимостей и не заменяет image scan. Raw JSON, stderr и metadata с командой, временем и SHA-256 lockfile сохранены в `.codex-tmp/remediation-completion-20260907/*-npm-audit-final.*`.

## По каждому override

Машиночитаемый [manifest](NPM_OVERRIDE_REVIEW_2026-09-07.json) содержит **все 22 записи**: selector, точный pin, каждого фактического родителя/его версию/исходный range, resolved package path/version, совпадение установленной версии, `satisfiesOriginalRange`, назначение, первичный источник, хеши manifests/locks/audits и тестовых логов. Диапазоны прочитаны из locks, не восстановлены по памяти.

| Область / selector | Фактический родитель и его запрос | Выбрано | Вывод |
|---|---|---|---|
| web+mobile / SDK | Angular CLI21.2.19 → exact1.26.0 | 1.30.0 | Явный выход за exact pin; проверенная v1-линия CLI dependency. Точный advisory, требующий именно1.30.0, в этой сверке не установлен |
| web+mobile / `@hono/node-server` | SDK1.30.0 → `^1.19.9 \|\| ^2.0.5` | 2.0.12 | Разрешён диапазоном; patch baseline HTTP adapter. Release2.0.12 описывает исправление копирования headers, его не выдаём за отдельную CVE |
| web+mobile / Angular build→undici | build21.2.19 → exact7.28.0 | 7.29.0 | Выход за exact pin ради опубликованных security fixes |
| web+mobile / jsdom→undici | jsdom28.1.0 → `^7.21.0` | 7.29.0 | Совместимый по range security pin |
| web+mobile / node-gyp→undici | node-gyp12.4.0 → `^6.25.0` | 6.28.0 | Отдельный v6 pin: не проталкивает v7 в native build helper |
| web+mobile / hono | SDK1.30.0 → `^4.11.4` | 4.12.34 | Совместимый pin опубликованных SSR/CORS/language/proxy fixes |
| web+mobile / ip-address | express-rate-limit8.5.2→`^10.2.0`; socks2.8.9→`^10.1.1` | 10.4.0 | Разрешён обоими ranges; выбранная версия добавляет Address6 byte-array validation |
| WA+worker / body-parser | Express4.22.2 → `~1.20.5` | 1.20.6 | Совместимый security pin; ошибочный `limit` теперь отвергается |
| WA+worker / qs | Express4.22.2 и body-parser1.20.6 → `~6.15.1` | 6.16.0 | Оба исходных ranges исключают новую minor; явный override для двух DoS fixes |
| WA / brace-expansion | minimatch9.0.9→`^2.0.2`; nested minimatch5.1.9→`^2.0.1` | 2.1.4 | Остаётся внутри v2 ranges; точная advisory attribution последней patch здесь не установлена |
| WA / wwebjs→puppeteer | whatsapp-web.js1.34.7 → exact24.38.0 | direct pin25.10.0 / `$puppeteer` | Осознанная major migration, требующая adapter и настоящего Chromium smoke; live account acceptance отдельно |
| WA / ip-address, js-yaml | Нет nodes и родительских requests в текущем lock | 10.4.0 / 4.3.1 | **Dormant**: сейчас не меняют граф. Кандидаты на удаление в отдельной проверенной housekeeping-правке; в read-only проходе сохранены |

Все web/mobile overrides помечены в locks как dev dependencies. Сборки и runtime-тесты проверяют инструменты/клиентский код, но отдельный сервер Angular CLI MCP в них не поднимался. Нельзя из этих результатов вывести совместимость каждого неиспользуемого MCP API или эксплуатацию этих библиотек публичным backend.

Первичные источники проверены 2026-09-07:

- [Undici7.29.0](https://github.com/nodejs/undici/releases/tag/v7.29.0) исправляет cache/header/cookie/retry advisory; [6.28.0](https://github.com/nodejs/undici/releases/tag/v6.28.0) содержит применимые исправления v6 и явно отделяет v7-only cache issues.
- [Hono4.12.34](https://github.com/honojs/hono/releases/tag/v4.12.34) перечисляет SSR/CORS/language/proxy fixes. [Node adapter2.0.12](https://github.com/honojs/node-server/releases/tag/v2.0.12) подтверждает выбранную patch и ограниченное описание изменения.
- [Body-parser history](https://github.com/expressjs/body-parser/blob/master/HISTORY.md) связывает1.20.6 с GHSA-v422-hmwv-36x6. [qs GHSA-4mjr-xmp4-gh2g](https://github.com/ljharb/qs/security/advisories/GHSA-4mjr-xmp4-gh2g) и [GHSA-x5fp-wj9c-mxmx](https://github.com/ljharb/qs/security/advisories/GHSA-x5fp-wj9c-mxmx) фиксируются6.16.0.
- [Puppeteer25 release](https://github.com/puppeteer/puppeteer/releases/tag/puppeteer-v25.0.0) документирует breaking changes. Локальный `whatsapp/puppeteer-compatibility.js` адаптирует owned connection lifecycle; источники зависимости не патчатся.

Сохранённая проверка: web697 runtime cases + production build; mobile148 runtime/229 node cases + последующие50 P19 targeted и production build; worker32 tests; gateway89 tests. Worker/WA actual hardened image smoke прошли 11:22/11:24 UTC: Chromium sandbox, worker offline OCR и readiness/drain; WA local/remote lifecycle на перехваченных fixture pages. **Live WhatsApp login/send не выполнялись.** Точные логи и хеши находятся в manifest. Старый mobile full-suite lock отличается от текущего только версией локального `@otziv/client-common`1.0.0→1.1.0; версии всех override и registry dependencies совпадают. Последний P19 build использует текущий lock.

Удалять активный override следует после того, как обновлённый parent сам задаёт не более старый проверенный security floor, а clean install, tests/build, runtime smoke и audit проходят без override. Для dormant записей сначала следует доказать неизменность package nodes/integrities. Это критерии следующей технической проверки, не уже выполненное удаление.

## Оставшиеся HIGH/CRITICAL

[Per-CVE index](UNRESOLVED_CVE_REVIEW_2026-09-07.json) объединяет последние сохранённые raw/triage отчёты этого пакета. Их хеши повторно сверены; image scans не перезапускались.

| Image snapshot / время UTC | Package/CVE rows | Distinct CVE | Available-fix HC |
|---|---:|---:|---:|
| worker /11:35:59 | 91 | 41 | 0 |
| whatsapp /11:34:55 | 105 | 49 | 0 |
| signals publisher /11:32:54 | 56 | 18 | 0 |

Всего **252 image/package/CVE rows, 49 различных CVE**. Повторы одного CVE между бинарными пакетами и образами не являются отдельными exploit-сценариями. Это ограниченный индекс трёх отчётов: он не заменяет более поздние root scans Keycloak/всего флота и не переименовывает старые backend/observer evidence в новые.

Для **каждого CVE** сохранены affected image/package/version/vendor status, source class, контекст, конкретный следующий шаг и требуемые роли reviewer. `assignedPerson`, `riskAcceptance`, `acceptanceExpiry` остаются `null`; `approvalStatus=NOT_REQUESTED_OR_GRANTED_BY_THIS_DOCUMENT`. Role — предложенный маршрут рассмотрения, не утверждение о назначенном сотруднике. Конкретный календарный срок и срок исключения не согласованы. До следующего решения о выпуске требуется фактическое рассмотрение, а не бесконечное автоматическое продление.

- Chromium/third-party content: проверить vendor fix для точного build; после обновления повторить sandbox/browser smokes. Наличие sandbox не доказывает отсутствие reachable browser issue.
- Native библиотеки: сверить distro/source package, binary/architecture и реальные callers. До такой проверки нельзя объявлять CVE false positive по одному заголовку или именам пакетов.
- Системные инструменты: проверить наличие исполняемого пути и требуемых привилегий; удалять действительно ненужные пакеты только с повторным runtime smoke. Неиспользуемый entrypoint не доказывает недостижимость всех функций библиотеки.

Таким образом, свежие npm graphs чистые, overrides инвентаризированы, а **остаточный контейнерный риск не принят и P06 security acceptance целиком не закрыта**. Документ не меняет scanner gate, не выдаёт VEX `not_affected` и не добавляет исключений.
