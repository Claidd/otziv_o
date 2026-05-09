# Analytics Aggregates Production Runbook

Пошаговая инструкция для безопасного включения агрегатной аналитики на production.
Старая аналитика остается fallback-слоем: если флаг чтения выключен, приложение работает по
старому пути.

## 0. Что должно быть по умолчанию

В обычном production-режиме до включения агрегатов:

```env
OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED=false
OTZIV_ANALYTICS_REBUILD_API_ENABLED=false
OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED=false
OTZIV_ANALYTICS_REBUILD_STARTUP_ENABLED=false
```

`OTZIV_ANALYTICS_REBUILD_API_ENABLED` нельзя оставлять включенным после проверки. Это служебный
API для пересборки и сверки.

## 1. Локальная проверка перед релизом

Перед production-деплоем запустить локальный prod-like verify:

```powershell
.\infrastructure\scripts\local\verify-analytics-aggregates.ps1
```

Для быстрого повтора без пересборки контейнеров:

```powershell
.\infrastructure\scripts\local\verify-analytics-aggregates.ps1 -NoBuild
```

Проверка должна закончиться строкой `Aggregate analytics verification passed.` и вернуть окружение
в safe mode: read flag `false`, rebuild API `false`, scheduler `false`.

## 2. Подготовка production

Зайти на сервер и перейти в каталог деплоя. В примерах ниже используется `/docker` и env-файл
`.env`, как в production deploy scripts.

```bash
cd /docker
ENV_FILE=.env

compose() {
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose -f docker-compose.yaml --env-file "$ENV_FILE" "$@"
  else
    docker compose -f docker-compose.yaml --env-file "$ENV_FILE" "$@"
  fi
}

env_value() {
  grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | sed 's/^"//;s/"$//'
}

set_env() {
  key="$1"
  value="$2"
  if grep -q "^$key=" "$ENV_FILE"; then
    sed -i "s|^$key=.*|$key=$value|" "$ENV_FILE"
  else
    printf "\n%s=%s\n" "$key" "$value" >> "$ENV_FILE"
  fi
}

BASE_URL="$(env_value OTZIV_APP_BASE_URL)"
KEYCLOAK_CLIENT_ID="$(env_value KEYCLOAK_ADMIN_CLIENT_ID)"
KEYCLOAK_CLIENT_SECRET="$(env_value KEYCLOAK_ADMIN_CLIENT_SECRET)"
```

Сделать свежий backup БД до backfill. Если ежедневный backup уже включен, убедиться, что последний
backup успешно создан. Для ручного dump можно использовать MySQL-контейнер:

```bash
mkdir -p data/manual-backups
compose exec -T mysql sh -c \
  'mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
  > "data/manual-backups/analytics-before-$(date +%Y%m%d-%H%M%S).sql"
```

## 3. Деплой с выключенным чтением

Сначала задеплоить новую версию с миграциями, но оставить агрегатное чтение выключенным:

```bash
set_env OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED false
set_env OTZIV_ANALYTICS_REBUILD_API_ENABLED false
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED false
set_env OTZIV_ANALYTICS_REBUILD_STARTUP_ENABLED false

compose up -d app nginx
curl -fsS "$BASE_URL/actuator/health"
```

Проверить, что таблицы агрегатов созданы:

```bash
compose exec -T mysql sh -c \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SHOW TABLES LIKE '\''analytics_%'\'';"'
```

Ожидаемые таблицы:

- `analytics_daily_total`
- `analytics_daily_user`
- `analytics_monthly_total`
- `analytics_monthly_user`

## 4. Временно включить rebuild API

Включить служебный API только на время backfill и compare:

```bash
set_env OTZIV_ANALYTICS_REBUILD_API_ENABLED true
set_env OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED false
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED false

compose up -d app
curl -fsS "$BASE_URL/actuator/health"
```

Получить service-account token. Токен не печатать в чатах и логах.

```bash
TOKEN="$(
  curl -fsS -X POST "$BASE_URL/keycloak/realms/otziv/protocol/openid-connect/token" \
    -d grant_type=client_credentials \
    -d client_id="$KEYCLOAK_CLIENT_ID" \
    -d client_secret="$KEYCLOAK_CLIENT_SECRET" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
)"

test -n "$TOKEN"
```

## 5. Backfill агрегатов

Определить первый месяц данных автоматически по сырьевым таблицам `zp`, `payment_check`,
`companies`, `leads`, `reviews`. Backfill идет до последнего найденного месяца данных. Прошлые
месяцы до текущего пересобираются как закрытые (`closed=true`), текущий и будущие найденные месяцы
как незакрытые (`closed=false`). Auto-detect отсекает даты раньше
`2023-01-01` и позже `2027-12-31`, чтобы случайные битые исторические или слишком будущие даты не
запускали бессмысленный backfill.

```bash
SOURCE_RANGE="$(
  curl -fsS "$BASE_URL/api/admin/analytics/aggregates/source-range" \
    -H "Authorization: Bearer $TOKEN"
)"
START_MONTH="$(printf '%s' "$SOURCE_RANGE" | sed -n 's/.*"firstMonth":"\([0-9]\{4\}-[0-9]\{2\}\)-[0-9]\{2\}".*/\1/p')"
END_MONTH="$(printf '%s' "$SOURCE_RANGE" | sed -n 's/.*"lastMonth":"\([0-9]\{4\}-[0-9]\{2\}\)-[0-9]\{2\}".*/\1/p')"
CURRENT_MONTH="$(date +%Y-%m)"
test -n "$START_MONTH"
test -n "$END_MONTH"

month="$START_MONTH"
while :; do
  closed=true
  if [ "$(printf '%s\n%s\n' "$CURRENT_MONTH" "$month" | sort | head -n1)" = "$CURRENT_MONTH" ]; then
    closed=false
  fi

  echo "Rebuilding $month closed=$closed"
  curl -fsS -X POST \
    "$BASE_URL/api/admin/analytics/aggregates/rebuild-month?month=$month&closed=$closed" \
    -H "Authorization: Bearer $TOKEN" \
    -o "analytics-rebuild-$month.json"

  grep -q '"matches":true' "analytics-rebuild-$month.json" || {
    echo "Admin compare failed after rebuilding $month"
    cat "analytics-rebuild-$month.json"
    exit 1
  }

  [ "$month" = "$END_MONTH" ] && break
  month="$(date -d "$month-01 +1 month" +%Y-%m)"
done
```

Если backfill упал на конкретном месяце, не включать read flag. Сохранить JSON-ответ,
посмотреть логи `app`, исправить причину и повторить пересборку этого месяца.

## 6. Compare-проверки

Выбрать реальные production-аккаунты для сверки:

```bash
CHECK_DATE="$(date +%F)"
ADMIN_USERNAME=alex
OWNER_USERNAME=hunt
WORKER_USER_ID=6
```

При необходимости посмотреть пользователей и роли:

```bash
compose exec -T mysql sh -c \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "
   SELECT u.id, u.username, GROUP_CONCAT(r.name ORDER BY r.name) roles
   FROM users u
   LEFT JOIN users_roles ur ON ur.user_id = u.id
   LEFT JOIN roles r ON r.id = ur.role_id
   GROUP BY u.id, u.username
   ORDER BY u.id
   LIMIT 50;"'
```

Запустить compare:

```bash
check_compare() {
  name="$1"
  url="$2"
  require_mismatch_zero="${3:-false}"
  echo "Checking $name"
  body="$(curl -fsS "$url" -H "Authorization: Bearer $TOKEN")"
  echo "$body" > "analytics-compare-$name.json"
  echo "$body" | grep -Eq '"matches"[[:space:]]*:[[:space:]]*true' || {
    echo "Compare failed: $name"
    cat "analytics-compare-$name.json"
    exit 1
  }
  if [ "$require_mismatch_zero" = "true" ]; then
    echo "$body" | grep -Eq '"mismatchCount"[[:space:]]*:[[:space:]]*0' || {
      echo "Compare has mismatches: $name"
      cat "analytics-compare-$name.json"
      exit 1
    }
  fi
}

check_compare "analyse-admin" \
  "$BASE_URL/api/admin/analytics/aggregates/compare-cabinet-analyse?username=$ADMIN_USERNAME&date=$CHECK_DATE&role=ADMIN"

check_compare "analyse-owner" \
  "$BASE_URL/api/admin/analytics/aggregates/compare-cabinet-analyse?username=$OWNER_USERNAME&date=$CHECK_DATE&role=OWNER"

check_compare "score" \
  "$BASE_URL/api/admin/analytics/aggregates/compare-score?date=$CHECK_DATE" \
  true

check_compare "team-owner" \
  "$BASE_URL/api/admin/analytics/aggregates/compare-team?username=$OWNER_USERNAME&date=$CHECK_DATE&role=OWNER" \
  true

check_compare "user-stats" \
  "$BASE_URL/api/admin/analytics/aggregates/compare-user-stats?userId=$WORKER_USER_ID&date=$CHECK_DATE"
```

Все ответы должны иметь `matches=true`. Для `score` и `team` также должен быть
`mismatchCount=0`.

## 7. Выключить rebuild API

После backfill и compare сразу выключить служебный API:

```bash
set_env OTZIV_ANALYTICS_REBUILD_API_ENABLED false
compose up -d app
curl -fsS "$BASE_URL/actuator/health"
```

Проверить флаги внутри контейнера:

```bash
compose exec -T app sh -c 'env | sort | grep OTZIV_ANALYTICS'
```

На этом этапе ожидается:

```env
OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED=false
OTZIV_ANALYTICS_REBUILD_API_ENABLED=false
OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED=false
```

## 8. Включить агрегатное чтение

Включить read flag и перезапустить приложение:

```bash
set_env OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED true
set_env OTZIV_ANALYTICS_REBUILD_API_ENABLED false
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED false

compose up -d app
curl -fsS "$BASE_URL/actuator/health"
```

Проверить через браузер под реальными пользователями:

- админ: аналитика и рейтинг;
- владелец: аналитика, команда, рейтинг;
- работник: личный кабинет и рейтинг.

Если что-то выглядит неправильно, сразу выполнить rollback из раздела 11.

## 9. Включить ежедневную пересборку

Для короткого canary можно оставить scheduler выключенным и вручную пересобирать текущий месяц.
Для постоянной работы с агрегатным чтением нужен ежедневный rebuild, иначе текущий месяц будет
устаревать.

После успешной проверки UI:

```bash
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED true
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_CRON '"0 30 3 * * *"'
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_ZONE Asia/Irkutsk
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_PREVIOUS_MONTH_WINDOW_DAYS 7
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_VERIFY_ADMIN_MONTH true
set_env OTZIV_ANALYTICS_REBUILD_API_ENABLED false

compose up -d app
curl -fsS "$BASE_URL/actuator/health"
```

Scheduler каждый запуск пересобирает текущий месяц как `closed=false`. Предыдущий месяц
пересобирается только в первые `PREVIOUS_MONTH_WINDOW_DAYS` дней месяца.

## 10. Мониторинг после включения

Первые 30-60 минут смотреть логи:

```bash
compose logs --tail=300 -f app
```

Отдельно искать ошибки и предупреждения агрегатов:

```bash
compose logs --tail=1000 app | grep -Ei 'AnalyticsAggregate|mismatch|ERROR|Exception'
```

Контрольные точки:

- `/actuator/health` отвечает `UP`;
- нет `mismatch` после scheduled rebuild;
- UI аналитики открывается у admin и owner;
- рейтинг открывается у admin, owner, manager, worker;
- rebuild API выключен.

## 11. Быстрый rollback

Откат не требует миграций и не удаляет агрегатные таблицы. Достаточно выключить read flag:

```bash
set_env OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED false
set_env OTZIV_ANALYTICS_REBUILD_API_ENABLED false
set_env OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED false

compose up -d app
curl -fsS "$BASE_URL/actuator/health"
compose exec -T app sh -c 'env | sort | grep OTZIV_ANALYTICS'
```

После этого `/api/cabinet/*` снова работает через legacy `PersonalService`.

## 12. Что не делать

- Не оставлять `OTZIV_ANALYTICS_REBUILD_API_ENABLED=true` после проверки.
- Не включать `OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED=true`, если compare дал расхождения.
- Не удалять и не переносить live-таблицы ради аналитики: агрегаты являются read-оптимизацией,
  а не архивированием.
- Не считать текущий месяц закрытым: для него нужен `closed=false`.
- Не включать scheduler до завершения backfill и compare.
