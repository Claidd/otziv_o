# Production Docker runbook для VPS

Продовый стек отделен от локального dev-запуска. Для разработки используй `compose.yaml`, а для проверки перед VPS - `compose.prod-local.yaml`. VPS-прод запускается через `docker-compose.yaml` и профиль Spring `prod`.

Важно: в репозитории есть и `compose.yaml`, и `docker-compose.yaml`. Поэтому на VPS всегда запускай prod с явным `-f docker-compose.yaml`.

## Что входит в prod

- `mysql` - основная база приложения, данные в Docker volume `docker_mysql_data`.
- `phpmyadmin` - выключен по умолчанию, включается только профилем `db-admin` и доступен через SSH-туннель `127.0.0.1:6571`.
- `keycloak-postgres` и `keycloak` - отдельная БД и Keycloak realm `otziv`.
- `app` - Spring Boot backend с `SPRING_PROFILES_ACTIVE=prod`.
- `external-review-worker` - опциональная браузерная проверка публикации отзывов; запускается только профилем `external-review`.
- `whatsapp_lika` и `whatsapp_vika` - внутренние WhatsApp Web шлюзы для менеджерских аккаунтов.
- `nginx` - публичная точка входа на `80/443`, Angular SPA, backend, Keycloak и Grafana.
- `prometheus`, `loki`, `alloy`, `grafana` - метрики, логи контейнеров и дашборды.
- `certbot` - ручной выпуск и продление сертификатов Let's Encrypt.
- `dozzle` - временный просмотр Docker-логов на `http://<server>:8081`.

## Файлы

- `docker-compose.yaml` - production Docker Compose stack для VPS.
- `docker-compose.build.yaml` - локальная сборка и push backend/frontend образов.
- `compose.yaml` - локальный dev stack, его не используем на VPS.
- `compose.prod-local.yaml` - локальная prod-like проверка перед VPS.
- `LOCAL_DEV.md` - команды запуска локальной разработки через IDE.
- `.env.prod.example` - шаблон переменных для домена `o-ogo.ru`.
- `backend/src/main/resources/application.yaml` и `application.properties` - базовая конфигурация Spring Boot.
- `backend/src/main/resources/application-prod.properties` - Spring Boot prod profile.
- `frontend/Dockerfile` - сборка Angular и Nginx runtime.
- `Dockerfile.whatsapp` и `whatsapp/` - Node.js шлюз на `whatsapp-web.js`.
- `infrastructure/nginx/prod.conf` - TLS reverse proxy.
- `infrastructure/keycloak/realm-config.prod.json` - production realm import.
- `infrastructure/loki/loki-config.yaml` - Loki retention/storage.
- `infrastructure/alloy/config.alloy` - сбор Docker/app логов в Loki.
- `infrastructure/scripts/prod/init-letsencrypt.sh` - первый выпуск сертификата.
- `infrastructure/scripts/prod/renew-letsencrypt.sh` - продление сертификата.

## Первый запуск на VPS

1. Установить Docker Engine и Docker Compose plugin.
2. Направить DNS `A`-запись `o-ogo.ru` на IP VPS.
3. Скопировать проект на VPS.
4. Создать env-файл:

```sh
cp .env.prod.example .env.prod
```

5. В `.env.prod` заменить все `CHANGE_ME` значения. Особенно важны:

- `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD`
- `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`
- `KEYCLOAK_ADMIN_CLIENT_SECRET`
- `JWT_SECRET`
- `MAIL_*`, `S3_*`, `TELEGRAM_*`, `OPENAI_API_KEY`
- `GRAFANA_ADMIN_PASSWORD`
- `APP_IMAGE`, `WEB_IMAGE` - теги опубликованных образов.

6. Выпустить сертификат через Certbot и поднять стек:

```sh
bash infrastructure/scripts/prod/init-letsencrypt.sh
```

Скрипт создаст временный self-signed сертификат, поднимет prod stack из `docker-compose.yaml`, выпустит Let's Encrypt для `o-ogo.ru`, положит `fullchain.pem`/`privkey.pem` в `data/nginx/certs` и перезагрузит Nginx.

Для безопасной проверки Certbot на VPS перед боевым выпуском поставь в `.env.prod`:

```env
CERTBOT_DRY_RUN=true
```

и запусти:

```sh
bash infrastructure/scripts/prod/init-letsencrypt.sh
```

Если dry-run прошел успешно, верни:

```env
CERTBOT_DRY_RUN=false
```

и запусти `init-letsencrypt.sh` еще раз уже для настоящего сертификата.

## Обычный запуск после настройки

```sh
docker compose -f docker-compose.yaml --env-file .env.prod pull
docker compose -f docker-compose.yaml --env-file .env.prod up -d
```

Если включена внешняя проверка опубликованных отзывов, отдельно запусти её профиль:

```sh
docker compose -f docker-compose.yaml --env-file .env.prod --profile external-review up -d external-review-worker
```

При `EXTERNAL_REVIEW_CHECK_ENABLED=false` backend не зависит от worker и штатный запуск/self-heal не пытается загружать его образ. Скрипт `deploy-prod.ps1` сохраняет этот безопасный режим по умолчанию; для отдельного будущего rollout worker требуется явный флаг `-EnableExternalReviewWorker`.

## MAX webhook

Для production MAX рекомендует Webhook вместо Long Polling. В приложении endpoint уже готов:

```text
https://o-ogo.ru/webhook/max
```

Перед регистрацией проверь, что в env-файле заполнены:

```env
OTZIV_APP_BASE_URL=https://o-ogo.ru
MAX_BOT_TOKEN=...
MAX_BOT_USERNAME=id380124742639_bot
MAX_BOT_WEBHOOK_SECRET=...
MAX_BOT_LONG_POLLING_ENABLED=false
```

При старте production backend сам зарегистрирует подписку в MAX, если `MAX_BOT_WEBHOOK_AUTO_REGISTER_ENABLED` не выключен. Обычно руками запускать ничего не нужно.

Ручная проверка или повторная регистрация на случай аварийной диагностики:

```powershell
.\infrastructure\scripts\prod\register-max-webhook.ps1 -EnvFile .env.prod
```

Если нужно посмотреть текущие подписки:

```powershell
.\infrastructure\scripts\prod\register-max-webhook.ps1 -EnvFile .env.prod -ListOnly
```

Если MAX уже хранит старую подписку на тот же URL и её нужно заменить:

```powershell
.\infrastructure\scripts\prod\register-max-webhook.ps1 -EnvFile .env.prod -DeleteExisting
```

Backend и скрипт подписывают бота на события `bot_started`, `bot_added`, `message_created` и передают `MAX_BOT_WEBHOOK_SECRET`; backend сверяет его с заголовком `X-Max-Bot-Api-Secret`.

## WhatsApp gateway

В prod поднимаются два внутренних WhatsApp-клиента:

```text
whatsapp_lika -> http://whatsapp_lika:3000, role=manager
whatsapp_vika -> http://whatsapp_vika:3000, role=manager
```

Они не требуют Keycloak, потому что не являются пользовательским интерфейсом. Контейнеры доступны только во внутренней Docker-сети, а входящие webhook-запросы в backend можно защитить общим секретом:

```env
WHATSAPP_WEBHOOK_SECRET=<long-random-value>
```

Backend получает список клиентов из compose-переменных:

```env
WHATSAPP_CLIENTS_0_ID=whatsapp_lika
WHATSAPP_CLIENTS_0_URL=http://whatsapp_lika:3000
WHATSAPP_CLIENTS_0_ROLE=manager
WHATSAPP_CLIENTS_1_ID=whatsapp_vika
WHATSAPP_CLIENTS_1_URL=http://whatsapp_vika:3000
WHATSAPP_CLIENTS_1_ROLE=manager
```

В compose для этих значений уже есть дефолты, поэтому для стандартного запуска их можно не дублировать в env-файле.

Backend-level health monitor for WhatsApp is disabled by default:

```env
WHATSAPP_HEALTH_MONITOR_ENABLED=false
WHATSAPP_HEALTH_MONITOR_RESTART_ENABLED=false
```

Обычно достаточно Docker `healthcheck` и `restart: unless-stopped`. Включать `WHATSAPP_HEALTH_MONITOR_RESTART_ENABLED=true` стоит только если backend-контейнеру осознанно дали доступ к Docker CLI.

Первичная авторизация делается один раз на каждый контейнер. Менеджер может открыть QR из интерфейса: правая панель -> кнопка `WhatsApp` -> страница `/cabinet/whatsapp`. Если интерфейс недоступен, на VPS открой логи и отсканируй QR:

```sh
docker compose -f docker-compose.yaml --env-file .env logs -f whatsapp_lika
docker compose -f docker-compose.yaml --env-file .env logs -f whatsapp_vika
```

В prod-like локальном запуске `whatsapp_lika` и `whatsapp_vika` намеренно не поднимаются: реальные WhatsApp Web-сессии держим только на проде. Локальная QR-страница будет работать только если вручную подключить внешний тестовый gateway и прописать `WHATSAPP_CLIENTS_*`.

Prod deploy-скрипты отдельно собирают `whatsapp_lika` и `whatsapp_vika`; если эти сервисы убрать из production compose, деплой остановится на этапе сборки WhatsApp-контейнеров.

После авторизации автосинхронизация WhatsApp-групп в разделе "Справочники" будет ходить в `GET /groups`, сравнивать invite-code с `company_url_chat` и сохранять `company_group_id`. В логах успешная проверка выглядит примерно так:

```text
WhatsApp group sync finished source=scheduled clients=2 groups=18 linked=3
```

Отправка сообщений идет так: заказ берет `manager.clientId`, компания берет `company.groupId`, backend вызывает `/send-group` у нужного WhatsApp-контейнера. Ответы из личных чатов приходят в `/webhook/whatsapp-reply`, ответы из групп - в `/webhook/whatsapp-group-reply`.

## Временный доступ к phpMyAdmin

phpMyAdmin не поднимается вместе с основным стеком и не публикуется наружу. Когда нужно проверить БД, запусти его на VPS отдельным профилем:

```sh
docker compose -f docker-compose.yaml --env-file .env --profile db-admin up -d phpmyadmin
```

С локального компьютера открой туннель:

```powershell
ssh -i "$env:USERPROFILE\.ssh\otziv_vps_ed25519" -p 22022 -L 6571:127.0.0.1:6571 hunt@95.213.248.152
```

После этого phpMyAdmin будет доступен локально на `http://127.0.0.1:6571`. Логин и пароль вводятся руками, из `.env.prod` они больше не передаются в контейнер phpMyAdmin для автологина.

После проверки выключи сервис:

```sh
docker compose -f docker-compose.yaml --env-file .env --profile db-admin stop phpmyadmin
```

## Сборка образов локально

Задай теги, собери и отправь их в Docker registry:

```powershell
$env:APP_IMAGE="claid38/otziv-app:2026-05-04-1"
$env:WEB_IMAGE="claid38/otziv-web:2026-05-04-1"
docker compose -f docker-compose.build.yaml build app nginx
docker compose -f docker-compose.build.yaml push app nginx
```

`external-review-worker` в обычную ручную сборку не входит. Для его осознанного
включения используй штатный `deploy-prod.ps1 -EnableExternalReviewWorker`, который
одновременно включает backend-переключатель, проверяет образ и запускает worker.

На VPS поставь такие же значения `APP_IMAGE` и `WEB_IMAGE` в `.env.prod`, затем сделай `pull` и `up -d`.

## Локальная prod-like проверка перед деплоем

Перед push/deploy сначала прогони локальный стек, который максимально повторяет VPS без TLS:

```powershell
.\infrastructure\scripts\local\prod-like-smoke.ps1
```

Что проверяется:

- backend запускается с `SPRING_PROFILES_ACTIVE=prod` и `spring.jpa.open-in-view=false`;
- backend/frontend собираются Dockerfile'ами, как перед публикацией образов;
- Nginx отдает production Angular build и проксирует `/api`, `/keycloak`, `/grafana`;
- MySQL, Keycloak, Prometheus, Loki, Alloy и Grafana живут в Docker-сети;
- smoke проходит по `http://localhost:8088/actuator/health`, Keycloak discovery и frontend;
- если Docker Desktop временно не видит Docker Hub, smoke сам переключается на offline backend rebuild из локально собранного jar.

Если нужно принудительно проверить backend-фикс офлайн, без попытки пересобрать frontend image:

```powershell
.\infrastructure\scripts\local\prod-like-smoke.ps1 -OfflineAppBuild
```

Этот режим собирает backend jar локальным Maven, перекладывает его в уже существующий runtime image `APP_IMAGE` и запускает тот же compose stack без удаления базы.

После успешного smoke уже можно собирать и пушить tagged images через `docker-compose.build.yaml` или запускать `deploy-prod.ps1`.

## Автоматический деплой с локального компьютера

Для обычного обновления prod можно запустить PowerShell-скрипт:

```powershell
.\infrastructure\scripts\prod\deploy-prod.ps1 `
  -VpsHost 203.0.113.10 `
  -VpsUser hunt `
  -VpsPort 22022 `
  -VpsPath /opt/otziv `
  -SshKey C:\Users\Hunt\.ssh\id_rsa `
  -RemoteEnvFile .env
```

Что делает скрипт:

- собирает и пушит `APP_IMAGE` и `WEB_IMAGE` через `docker-compose.build.yaml`;
- только при явном `-EnableExternalReviewWorker` дополнительно собирает, пушит и разворачивает `EXTERNAL_REVIEW_WORKER_IMAGE`, одновременно включая hard-switch `EXTERNAL_REVIEW_CHECK_ENABLED`; без флага worker остаётся остановлен, а hard-switch принудительно сохраняется `false`;
- загружает на VPS `docker-compose.yaml`, `.env.prod` и prod-конфиги из `infrastructure`;
- до замены файлов и до запуска Flyway создаёт обязательный зашифрованный DB-backup с отдельным `DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64`, проверяет HMAC/расшифровку/gzip на VPS и скачивает копию в `%USERPROFILE%\.otziv\backups\pre-deploy\<tag>`;
- до backup отключает и останавливает `otziv-prod-up.timer` и активный oneshot-сервис, сохраняет исходные состояния enable/active в защищённом lock-каталоге, затем удерживает один durable deploy-lock до завершения rollout, поэтому self-heal, перезагрузка VPS и второй deploy не могут вклиниться между снимком БД и миграцией;
- сохраняет DB-backup в `.deploy-backups/<tag>/`, а старые `docker-compose.yaml` и env-файл — в уникальном `.deploy-backups/<tag>/rollout-<id>/`, поэтому повтор того же тега не затирает исходный rollback;
- при первом переходе с прежней раскладки сертификатов копирует `data/nginx/o-ogo.crt`/`o-ogo.key` в `data/nginx/certs/fullchain.pem`/`privkey.pem`, если новых файлов еще нет;
- проверяет неизменность Flyway history после DB-backup, затем последовательно обновляет `app` и остальные обязательные сервисы; optional worker участвует только при явном opt-in;
- устанавливает version-controlled `/usr/local/sbin/otziv-prod-up.sh` и оба systemd unit-файла: helper пропускает запуск при deploy-lock, восстанавливает обычные сервисы и запускает профиль `external-review` только когда hard-switch равен `true`; timer планирует первый запуск относительно каждого своего старта, поэтому после deploy `stop/start` не остаётся в состоянии `active (elapsed)` без следующего запуска;
- после health-check backend fail-closed обновляет MAX webhook и требует ответ `success=true`; при ошибке self-heal остаётся отключённым и остановленным, а deploy-lock сохраняется для ручной проверки;
- публикует переданный APK только после финальных health-check нового backend и обязательных сервисов.

Для релиза 5.50 `APP_MEMORY_LIMIT` обязателен и должен быть не ниже `2304m`: фактический пик RSS новой сборки превышает 1.7 GiB, поэтому прежний лимит `1536m` небезопасен. `JAVA_OPTS` при этом менять не требуется.

Перед первым запуском на локальном компьютере нужен `docker login`, а на VPS должны быть Docker Engine и Docker Compose plugin или standalone-команда `docker-compose`. Можно добавить к команде флаг `-DockerLogin`, чтобы скрипт сам запустил локальный `docker login` перед сборкой и push. По умолчанию скрипт берет локальный `.env.prod`, обновляет в его временной копии `APP_IMAGE`/`WEB_IMAGE` на новый тег и загружает копию на VPS. Если на VPS используется файл `.env`, передай `-RemoteEnvFile .env`.

Если секретный `.env.prod` уже настроен на VPS и его не нужно перезаписывать, добавь флаг:

```powershell
.\infrastructure\scripts\prod\deploy-prod.ps1 `
  -VpsHost 203.0.113.10 `
  -VpsUser hunt `
  -VpsPort 22022 `
  -VpsPath /opt/otziv `
  -SshKey C:\Users\Hunt\.ssh\id_rsa `
  -RemoteEnvFile .env `
  -SkipEnvUpload
```

В этом режиме скрипт сохранит серверный env-файл и обновит в нем теги `APP_IMAGE`, `WEB_IMAGE` и `EXTERNAL_REVIEW_WORKER_IMAGE`. Серверный env всё равно должен содержать отдельный `DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64`.

### Восстановление pre-deploy DB-backup

Формат `OTZIV-PREDEPLOY-DB-V2` — AES-256-CBC с PBKDF2-SHA256 (200000 итераций), отдельным производным HMAC-SHA256 и аутентифицированными исходными charset/collation schema. Ключ не совпадает с ключами credential-полей или плановых backup. Обычный deploy потоково сжимает и шифрует dump, поэтому незашифрованная копия на диск не записывается. Сначала проверь выбранную пару файлов:

```sh
artifact=.deploy-backups/5.50/pre-deploy-5.50-<timestamp>-<id>.sql.gz.enc
bash infrastructure/scripts/prod/create-pre-deploy-db-backup.sh verify "$artifact" "$artifact.manifest" .env
```

Проверка и расшифровка не меняют БД. Сам restore выполняется только при подтверждённом rollback: сначала отключи автозапуск timer, останови self-heal и все write-path сервисы, затем полностью пересоздай schema. Импорт поверх более новой schema запрещён — иначе таблицы из новых миграций останутся без соответствующей записи Flyway.

```sh
sudo systemctl disable --now otziv-prod-up.timer
sudo systemctl stop otziv-prod-up.service
docker compose -f docker-compose.yaml --env-file .env --profile external-review stop nginx app whatsapp_lika whatsapp_vika external-review-worker
bash infrastructure/scripts/prod/create-pre-deploy-db-backup.sh restore-clean \
  "$artifact" "$artifact.manifest" .env my-mysql \
  I_UNDERSTAND_THIS_REPLACES_PRODUCTION_DATABASE
```

`restore-clean` дополнительно откажется работать, пока timer разрешён к автозапуску, активен сам timer/oneshot-сервис или работает любой write-path сервис; затем полностью удалит и пересоздаст schema с исходными charset/collation из аутентифицированного зашифрованного backup, импортирует проверенный dump и оставит writers остановленными. После импорта верни сохранённые `docker-compose.yaml` и env нужного релиза, запусти соответствующие образы и проверь backend/БД. `otziv-prod-up.timer` включай только после успешной проверки.

При ошибке до завершения публикации и всех health-check скрипт намеренно оставляет `/docker/.deploy.lock.d`, а timer — disabled и stopped. Сначала убедись, что `deploy-prod.ps1`, SSH backup и миграция уже не выполняются, проверь контейнеры, логи и backup. Только после подтверждённого восстановления удали именно этот lock-каталог и верни timer командой `sudo systemctl enable --now otziv-prod-up.timer`. После включения обязательно проверь `systemctl status otziv-prod-up.timer`: состояние должно быть `active (waiting)`, а `NextElapseUSecMonotonic` — конечным, не `infinity`. Если SSH оборвался во время pre-deploy backup, состояние результата неоднозначно: автоматический cleanup намеренно не выполняется, пока оператор не проверит процессы и содержимое lock-каталога.

После успешных health-check и публикации APK начинается только финальная передача управления self-heal. Если SSH оборвётся именно в этом коротком окне, новый релиз уже считается применённым: timer и lock могут быть в любом из двух безопасных конечных состояний. Установленный helper уважает оставшийся lock. Перед повторным deploy проверь оба состояния вручную; не откатывай уже проверенный release автоматически.

## Локальный Docker запуск

`compose.yaml` поднимает Angular отдельно как `frontend` на `http://localhost:4200`.
Backend в этом режиме стартует со Spring profile `prod`, подключается к MySQL по docker-сети и проверяет Keycloak-токены с issuer `http://localhost:8180/realms/otziv`.

```powershell
docker compose -f compose.yaml up -d --build app frontend
```

В prod отдельного Angular-сервиса нет: Angular собирается в `WEB_IMAGE` через `frontend/Dockerfile`, а `docker-compose.yaml` запускает готовый nginx-образ.

## Продление сертификатов

Добавить в cron на VPS, например раз в сутки:

```sh
0 4 * * * cd /path/to/otziv && bash infrastructure/scripts/prod/renew-letsencrypt.sh >> data/nginx/logs/certbot-renew.log 2>&1
```

Проверить продление без замены текущего сертификата можно так: временно поставь `CERTBOT_DRY_RUN=true` в `.env.prod` и выполни `bash infrastructure/scripts/prod/renew-letsencrypt.sh`, затем верни `false`.

## Проверка

```sh
docker compose -f docker-compose.yaml --env-file .env.prod ps
docker compose -f docker-compose.yaml --env-file .env.prod logs -f nginx app keycloak
curl -k https://o-ogo.ru/actuator/health
```

Публично доступны только `80` и `443`. MySQL, Keycloak, Prometheus, Loki, Alloy и Grafana находятся во внутренней Docker-сети; Grafana открывается через `https://o-ogo.ru/grafana/`.

## Важные заметки

- Keycloak импортирует prod realm только при первом создании realm. Если realm уже существует, меняй настройки через админку или пересоздавай Keycloak volume осознанно.
- `/actuator/prometheus` закрыт снаружи Nginx, но Prometheus скрейпит его внутри Docker-сети.
- `LEAD_SYNC_OUTBOUND_ENABLED=false` в шаблоне защищает от случайной исходящей синхронизации на тот же домен. Включай только когда точно нужен outbound sync.
- Если нужен сертификат еще и для `www.o-ogo.ru`, сначала настрой DNS, затем добавь `CERTBOT_EXTRA_DOMAINS=www.o-ogo.ru` в `.env.prod`.
