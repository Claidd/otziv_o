# Local development with Docker infrastructure

Этот режим нужен, когда backend и Angular запускаются из IDE/терминала на Windows, а MySQL, Keycloak, Grafana, Loki, Alloy и остальные сервисы работают в Docker.

## 1. Остановить Docker backend/frontend/nginx

Если до этого запускался полный Docker stack, останови контейнеры приложения:

```powershell
docker compose -f compose.yaml stop app frontend nginx
```

## 2. Поднять инфраструктуру

```powershell
docker compose -f compose.ide.yaml -p otziv up -d
```

Важно: `compose.ide.yaml` уже сам подключает нужные сервисы из `compose.yaml`, поэтому в IDEA можно указывать только этот файл.

В этом режиме:

- MySQL доступен backend из IDE как `localhost:3307`.
- Keycloak доступен как `http://localhost:8180`.
- Grafana доступна как `http://localhost:3000`.
- phpMyAdmin в prod/prod-like режимах выключен по умолчанию; для IDE-стека смотри настройки используемого compose-файла.
- Prometheus скрейпит backend из IDE по `host.docker.internal:8080`.

## 3. Запустить backend в IntelliJ IDEA

Run configuration:

- Main class: `com.hunt.otziv.OtzivOApplication`
- Working directory: `D:\Java\otziv\backend`
- Active profile: `dev`

Или VM option:

```text
-Dspring.profiles.active=dev
```

`application-dev.properties` уже настроен на:

- MySQL: `jdbc:mysql://localhost:3307/otziv`
- Keycloak issuer: `http://localhost:8180/realms/otziv`

## 4. Запустить Angular локально

```powershell
cd frontend
npm start
```

Angular откроется на `http://localhost:4200`, а `/api` и `/actuator` будут проксироваться на локальный backend `http://localhost:8080` через `frontend/proxy.conf.json`.

## 5. Остановить инфраструктуру

```powershell
docker compose -f compose.ide.yaml -p otziv down
```

Если нужно сохранить базы, используй `down` без `-v`.

## Prod-like проверка перед VPS

Перед деплоем можно поднять локально почти тот же стек, что и на VPS: Spring profile `prod`, собранный backend image, production Angular build в Nginx, MySQL, Keycloak, Grafana/Loki/Prometheus/Alloy.

```powershell
.\infrastructure\scripts\local\prod-like-smoke.ps1
```

Скрипт сам создаст `.env.prod-local` из `.env.prod-local.example`, выполнит `docker compose -f compose.prod-local.yaml --env-file .env.prod-local up -d --build`, дождется `/actuator/health`, Keycloak realm и frontend на `http://localhost:8088`. Если Docker Desktop временно не видит Docker Hub, скрипт сам переключится на offline backend rebuild из локально собранного jar и не будет удалять volume.

Если нужно принудительно обновить только backend image из локально собранного jar, без попытки пересобрать frontend image:

```powershell
.\infrastructure\scripts\local\prod-like-smoke.ps1 -OfflineAppBuild
```

Этот режим полезен для быстрой проверки backend-фиксов, когда frontend не менялся.

Полезные адреса:

- приложение: `http://localhost:8088`
- MySQL: `127.0.0.1:3307`
- Keycloak: `http://localhost:8088/keycloak`
- Grafana: `http://localhost:8088/grafana`
- Dozzle logs: `http://localhost:8089`

phpMyAdmin для prod-like проверки поднимается только вручную:

```powershell
docker compose -f compose.prod-local.yaml --env-file .env.prod-local --profile db-admin up -d phpmyadmin
```

После этого он будет доступен на `http://127.0.0.1:6572`. Логин и пароль вводятся руками. Выключить:

```powershell
docker compose -f compose.prod-local.yaml --env-file .env.prod-local --profile db-admin stop phpmyadmin
```

По умолчанию prod-like стек использует отдельный volume `otziv-prod-local_mysql_data`. Если нужно проверить именно старую локальную базу из IDE-стека, укажи в `.env.prod-local`:

```env
LOCAL_MYSQL_VOLUME=otziv_mysql_data
```

Для проверки с данными VPS лучше восстановить dump в отдельный локальный volume и указывать его через `LOCAL_MYSQL_VOLUME`, чтобы не смешивать dev-данные и prod-like smoke.

Если после legacy migration пользователь оказался клиентом, значит у него в локальной MySQL не было рабочей роли. Для локальной проверки можно назначить пользователя владельцем:

```powershell
.\infrastructure\scripts\local\promote-local-owner.ps1 -Username hunt -ManagerId 1
```

После этого нужно выйти из приложения и войти снова, потому что роли уже лежат в JWT-токене браузера.

Остановить prod-like стек:

```powershell
docker compose -f compose.prod-local.yaml --env-file .env.prod-local down
```

Если нужна полностью чистая база и чистый Keycloak:

```powershell
docker compose -f compose.prod-local.yaml --env-file .env.prod-local down -v
```
