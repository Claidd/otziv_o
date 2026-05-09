# Analytics Aggregates

Этот документ фиксирует первый шаг перехода от тяжелых выборок по live-таблицам к агрегатам.
Старая логика остается рабочим fallback-слоем, пока новые таблицы не будут заполнены и подключены
к чтению.

Production-порядок backfill, compare, включения флага и отката описан отдельно:
`ANALYTICS_AGGREGATES_RUNBOOK.md`.

## Текущие источники

Основные экраны аналитики сейчас собираются в `PersonalServiceImpl` и близких сервисах:

- `cabinet/analyse`: зарплаты из `zp`, чеки из `payment_check`, далее группировка по дням и месяцам в Java.
- `cabinet/team`: пользователи, роли, менеджеры/работники плюс месячные суммы по `zp`, `payment_check`, лидам и заказам.
- Рейтинг: `zp`, `payment_check`, новые компании, новые заказы, опубликованные/ожидающие отзывы, лиды.
- Личный кабинет работника: дневные и месячные показатели пользователя на основе `zp`.

Ограничение последними двумя годами сейчас спрятано в сервисах `ZpServiceImpl` и
`PaymentCheckServiceImpl`. Это нужно сохранить как legacy-режим до полного перехода.

## Новые таблицы

### `analytics_daily_user`

Дневной агрегат по пользователю. Нужен для текущего месяца, личного кабинета, быстрых графиков
по дням и точного досчета периода, который еще не закрыт месячным агрегатом.

Одна строка = один пользователь + одна дата + роль пользователя на момент пересчета.

### `analytics_monthly_user`

Месячный агрегат по пользователю. Нужен для выборок по ролям, менеджерам, владельцам и страницам
команды/рейтинга, где нужно показать вклад конкретных сотрудников.

Одна строка = один пользователь + первый день месяца + роль пользователя на момент пересчета.

### `analytics_monthly_total`

Месячный агрегат по видимости. Нужен, чтобы администратор и владелец получали общие показатели за
период за 1-2 запроса без суммирования сотен пользовательских строк.

Одна строка = месяц + `scope_key`.

### `analytics_daily_total`

Дневной агрегат по видимости. Нужен для текущего месяца и коротких периодов вроде "вчера" и
"неделя" без двойного учета атрибуционных user-строк.

Одна строка = дата + `scope_key`.

Предлагаемые `scope_key`:

- `ADMIN:ALL` - все данные для администратора.
- `OWNER:<userId>` - данные, видимые конкретному владельцу.
- `MANAGER:<userId>` - резерв для быстрых менеджерских срезов, если понадобится.

`scope_user_id` хранит пользователя-владельца scope, когда он есть. Для `ADMIN:ALL` это `NULL`.

## Текущий месяц

Текущий месяц не нужно постоянно перезаписывать в `analytics_monthly_user` и
`analytics_monthly_total`. Безопаснее держать месячные таблицы как закрытые/пересчитанные периоды,
а текущий месяц считать так:

1. Взять закрытые месяцы из `analytics_monthly_total` или `analytics_monthly_user`.
2. Добавить текущий месяц из `analytics_daily_total` для общих графиков администратора/владельца
   или из `analytics_daily_user` для персональных/командных срезов.
3. При необходимости добавить сегодняшний live-досчет из основных таблиц, если daily job еще не
   прошел.

Так мы не теряем точность в течение месяца и не превращаем месячную таблицу в постоянно мутирующий
операционный источник.

## Роли и видимость

Для администратора достаточно `analytics_monthly_total` со scope `ADMIN:ALL`.

Для владельца есть два варианта чтения:

- быстрый общий график: `analytics_monthly_total` со scope `OWNER:<ownerUserId>`;
- детализация команды: `analytics_monthly_user` по пользователям, которые входят в видимость
  владельца.

Для менеджера и работника лучше использовать `analytics_monthly_user` и `analytics_daily_user`,
потому что им чаще нужны персональные и командные показатели, а не весь общий итог.

Исторические смены ролей/команд нужно учитывать при backfill job: агрегат фиксирует `role_name`,
`manager_user_id` и `manager_id` на момент пересчета. Если позже появится строгий аудит
исторической оргструктуры, его можно подключить к пересборке агрегатов без изменения старой
аналитики.

## Первый безопасный инкремент

1. Создать только таблицы агрегатов.
2. Добавить backfill/rebuild job отдельным шагом.
3. Добавить новый read-path за feature flag или отдельным методом.
4. Сравнить результаты старого и нового путей на одинаковых периодах.
5. Только после этого переключать экраны аналитики.

Агрегатный read-path добавлен для:

- `GET /api/cabinet/profile`;
- `GET /api/cabinet/user-info`;
- `GET /api/cabinet/team` для `OWNER`-ветки;
- `GET /api/cabinet/analyse`;
- `GET /api/cabinet/score`.

Он выключен по умолчанию:

- `OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED=false`

Если включить флаг, `/analyse` сначала пробует собрать `StatDTO` из `analytics_monthly_total` и
`analytics_daily_total`. `/score` сначала пробует собрать рейтинг из `analytics_monthly_user` или
`analytics_daily_user`. `/profile` и `/user-info` сначала пробуют собрать `UserStatDTO` из
`analytics_monthly_user` и `analytics_daily_user`. `/team` для владельца сначала пробует собрать
месячные показатели команды из `analytics_monthly_user`/`analytics_daily_user`. Если агрегатов для
выбранного периода еще нет, эти endpoint автоматически возвращаются к старому `PersonalService`.
`ADMIN` и `MANAGER` ветки `/team` пока оставлены на текущем поведении.

`/api/cabinet/analyse` поддерживает период месячных графиков:

- без параметров - текущий и предыдущий календарный год;
- `allTime=true` - вся история агрегатов;
- `from=yyyy-MM-dd&to=yyyy-MM-dd` - ручной диапазон, где `to` не позже выбранной `date`.

Оперативные карточки "за день/неделю/месяц/год" по-прежнему считаются относительно `date`, а
период управляет именно месячными графиками.

## Пересчет

`AnalyticsAggregateRebuildService` пока пересчитывает только те метрики, которые можно безопасно
восстановить по датам:

- зарплаты и количество оплаченных отзывов из `zp`;
- суммы и количество чеков из `payment_check`;
- новые компании из `companies`;
- лиды и лиды в работе из `leads`;
- опубликованные отзывы из `reviews`.

Операционные очереди вроде "Новый", "Коррекция", "на публикации" и "в выгуле" не являются
полноценной историей: сейчас они зависят от текущего статуса записи. Их нужно добавлять отдельным
snapshot-шагом, который будет фиксировать состояние на конкретную дату вперед, а не пытаться
задним числом восстановить то, чего нет в истории изменений.

Для ручной локальной проверки есть закрытый API:

- `POST /api/admin/analytics/aggregates/rebuild-month?month=2026-05&closed=false`
- `GET /api/admin/analytics/aggregates/source-range`
- `GET /api/admin/analytics/aggregates/compare-admin-month?month=2026-05`
- `GET /api/admin/analytics/aggregates/compare-cabinet-analyse?username=admin&date=2026-05-09`
- `GET /api/admin/analytics/aggregates/compare-score?date=2026-05-09`
- `GET /api/admin/analytics/aggregates/compare-team?username=admin&date=2026-05-09&role=ADMIN`
- `GET /api/admin/analytics/aggregates/compare-user-stats?userId=1&date=2026-05-09`

Он выключен по умолчанию и появляется только при свойстве
`otziv.analytics.rebuild.api-enabled=true` (`OTZIV_ANALYTICS_REBUILD_API_ENABLED=true` в env).
Доступ дополнительно ограничен ролью `ADMIN`.

Повторяемая локальная проверка вынесена в скрипт:

```powershell
.\infrastructure\scripts\local\verify-analytics-aggregates.ps1
```

Скрипт не меняет `.env`: он временно поднимает prod-like окружение с
`OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED=true` и
`OTZIV_ANALYTICS_REBUILD_API_ENABLED=true`, определяет первый месяц сырьевых данных через
`source-range`, пересобирает агрегаты до выбранного месяца, прогоняет compare-проверки и реальные
`/api/cabinet/*` endpoint'ы через Keycloak impersonation, а затем возвращает окружение в безопасный
режим с выключенными флагами. Auto-detect учитывает только окно `2023-01-01..2027-12-31`, чтобы
битые старые или слишком будущие значения не расширяли backfill. Для быстрого повтора без пересборки
можно передать `-SkipRebuild`.
Если нужно временно ограничить окно проверки, можно передать `-RebuildStartMonth yyyy-MM`.

`compare-score` сверяет только поля рейтинга, которые уже есть в агрегатах:

- `salary`;
- `totalSum`;
- `zpTotal`;
- `newCompanies`;
- `order1Month`;
- `review1Month`;
- `leadsNew`;
- `leadsInWork`;
- `percentInWork`.

Поля `newOrders`, `correctOrders`, `inVigul`, `inPublish`, `imageId` и `userId` в compare-ответе
помечены как skipped. Первые четыре являются живыми очередями по текущим статусам и не должны
восстанавливаться как месячная история без отдельного snapshot-механизма. `imageId` и `userId`
относятся к пользовательскому профилю, а не к финансовым агрегатам.

`compare-team` поддерживает роли `ADMIN` и `OWNER`. Он сверяет командные месячные поля, которые
уже можно получить из `analytics_monthly_user`/`analytics_daily_user`:

- менеджеры: `sum1Month`, `order1Month`, `review1Month`, `payment1Month`;
- маркетологи и операторы: `sum1Month`, `order1Month`, `review1Month`, `leadsNew`,
  `leadsInWork`, `percentInWork`;
- работники: `sum1Month`, `order1Month`, `review1Month`.

Для менеджерского `payment1Month` агрегатный путь специально берет `analytics_daily_user` с 1 числа
до выбранной даты, потому что legacy-метод считает выручку не за полный месяц, а до выбранного дня.
Рабочие статусы `newOrder`, `inCorrect`, `intVigul` и `publish` пока skipped: это живые очереди,
а не исторические финансовые агрегаты.

`compare-user-stats` сверяет личный кабинет конкретного пользователя (`getWorkerReviews`) по
финансовым полям, которые сейчас реально заполняет legacy-метод:

- профильные поля `id`, `fio`, `imageId`, `coefficient`;
- дневной и месячный JSON-графики ЗП;
- суммы и количества ЗП за день, неделю, месяц, год;
- проценты для сумм и количеств.

Поля отзывов (`reviewsGet*`, `reviewsPublish*`, `reviewsPublished*`, `reviewsPay*`), `percentNoPay`
и `avgPublish1Day` помечены как skipped, потому что этот legacy-метод их сейчас не заполняет.

Ежедневная автоматическая пересборка предыдущего месяца нужна только как страховка от правок задним
числом: если в первые дни нового месяца исправили чек, ЗП, заказ, лид или отзыв с датой прошлого
месяца, закрытый агрегат должен это подобрать. Чтобы не делать лишнюю работу, ее можно ограничить
первыми 3-7 днями месяца или запускать вручную после таких исправлений через закрытый rebuild API.

Для регулярной пересборки есть `AnalyticsAggregateScheduledRebuildJob`. Он выключен по умолчанию:

- `OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED=false`
- `OTZIV_ANALYTICS_REBUILD_SCHEDULE_CRON=0 30 3 * * *`
- `OTZIV_ANALYTICS_REBUILD_SCHEDULE_ZONE=Asia/Irkutsk`
- `OTZIV_ANALYTICS_REBUILD_SCHEDULE_PREVIOUS_MONTH_WINDOW_DAYS=7`
- `OTZIV_ANALYTICS_REBUILD_SCHEDULE_VERIFY_ADMIN_MONTH=true`

Когда job включен, он каждый запуск пересобирает текущий месяц как незакрытый (`closed=false`).
Предыдущий месяц пересобирается как закрытый (`closed=true`) только если текущий день месяца меньше
или равен `PREVIOUS_MONTH_WINDOW_DAYS`. При `0` предыдущий месяц автоматически не трогается.
Верификация `ADMIN:ALL` после пересборки логирует расхождение, но не останавливает приложение.

Для одноразовой проверки при старте prod-like контейнера есть startup-runner. Он тоже выключен
по умолчанию и требует явного месяца:

- `OTZIV_ANALYTICS_REBUILD_STARTUP_ENABLED=true`
- `OTZIV_ANALYTICS_REBUILD_STARTUP_MONTH=2026-05`
- `OTZIV_ANALYTICS_REBUILD_STARTUP_CLOSED=false`

Runner пересчитывает месяц, сравнивает `ADMIN:ALL` с сырыми источниками и останавливает старт
приложения, если найдено расхождение.
