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
- phpMyAdmin доступен как `http://localhost:6571`.
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
