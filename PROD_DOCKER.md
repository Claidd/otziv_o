# Production Docker runbook для VPS

Продовый стек отделен от локального dev-запуска. Для разработки продолжай использовать текущие `compose.yaml`/`composeLocal.yaml`, `npm start` и dev/local Spring-профили. VPS-прод запускается через `docker-compose.yaml` и профиль Spring `prod`.

Важно: в репозитории есть и `compose.yaml`, и `docker-compose.yaml`. Поэтому на VPS всегда запускай prod с явным `-f docker-compose.yaml`.

## Что входит в prod

- `mysql` - основная база приложения, данные в Docker volume `mysql_data`.
- `keycloak-postgres` и `keycloak` - отдельная БД и Keycloak realm `otziv`.
- `app` - Spring Boot backend с `SPRING_PROFILES_ACTIVE=prod`.
- `nginx` - публичная точка входа на `80/443`, Angular SPA, backend, Keycloak и Grafana.
- `prometheus`, `loki`, `alloy`, `grafana` - метрики, логи контейнеров и дашборды.
- `certbot` - ручной выпуск и продление сертификатов Let's Encrypt.

## Файлы

- `docker-compose.yaml` - production Docker Compose stack для VPS.
- `docker-compose.build.yaml` - локальная сборка и push backend/frontend образов.
- `compose.yaml` - локальный dev stack, его не используем на VPS.
- `compose.ide.yaml` - локальный IDE-режим одним compose-файлом: backend/frontend на Windows, инфраструктура в Docker.
- `LOCAL_DEV.md` - команды запуска локальной разработки через IDE.
- `.env.prod.example` - шаблон переменных для домена `o-ogo.ru`.
- `backend/src/main/resources/application-docker.properties` - Spring Boot profile для локального Docker stack.
- `backend/src/main/resources/application-prod.properties` - Spring Boot prod profile.
- `frontend/Dockerfile` - сборка Angular и Nginx runtime.
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

## Сборка образов локально

Задай теги, собери и отправь их в Docker registry:

```powershell
$env:APP_IMAGE="claid38/otziv-app:2026-05-04-1"
$env:WEB_IMAGE="claid38/otziv-web:2026-05-04-1"
docker compose -f docker-compose.build.yaml build
docker compose -f docker-compose.build.yaml push
```

На VPS поставь такие же значения `APP_IMAGE` и `WEB_IMAGE` в `.env.prod`, затем сделай `pull` и `up -d`.

## Локальный Docker запуск

`compose.yaml` поднимает Angular отдельно как `frontend` на `http://localhost:4200`.
Backend в этом режиме стартует со Spring profile `docker`, подключается к MySQL по docker-сети и проверяет Keycloak-токены с issuer `http://localhost:8180/realms/otziv`.

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
