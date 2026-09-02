# Интеграция otziv и Multi-browser

## Рекомендуемая production-схема

`otziv` и browser-сервис размещаются на разных VPS. Browser VPS имеет доступ к Docker socket и запускает тяжёлые Chromium-контейнеры; отделение уменьшает blast radius и позволяет масштабировать браузеры независимо.

- `otziv VPS -> https://browser.example.com`: server-to-server REST с единственным `X-API-Key`.
- `пользователь -> https://o-ogo.ru/browser-vnc/...`: same-origin noVNC; Nginx otziv передаёт HTTPS/WebSocket на Traefik browser VPS.
- Откройте 443 browser VPS только для IP otziv VPS, административных адресов и health-мониторинга. Порт 8081 наружу не публикуйте.
- VNC URL содержит случайный route token, VNC password передаётся отдельно. Оба значения краткоживущие, не логируются и не сохраняются в таблице сессий.

Browser VPS:

```dotenv
APP_SECURITY_API_KEY=<64+ random hex chars>
PUBLIC_BASE_URL=https://o-ogo.ru/browser-vnc
MULTIBROWSER_PUBLIC_HOST=browser.example.com
ACME_EMAIL=ops@example.com
```

Otziv VPS:

```dotenv
MULTIBROWSER_BASE_URL=https://browser.example.com
MULTIBROWSER_API_KEY=<exactly the same key>
MULTIBROWSER_CONNECTION_MODE=PROXY
MULTIBROWSER_PROXY_URL=<new sticky provider URL>
MULTIBROWSER_PUBLIC_UPSTREAM=https://browser.example.com
MULTIBROWSER_PUBLIC_HOST=browser.example.com
```

`MULTIBROWSER_API_KEY` обязателен во всех режимах. В `PROXY` также обязателен непустой `MULTIBROWSER_PROXY_URL`; в `DIRECT` значение proxy очищается, а browser-сервис должен быть запущен с `BROWSER_ALLOW_DIRECT=true`. Backend проверяет эту конфигурацию при старте. Ключ и proxy credentials не выводятся в логи. Nginx отключает access log для capability-path `/browser-vnc/` и проверяет TLS upstream.

## Локальный запуск в общей Docker-сети

На Linux/WSL2 оба стека можно запустить на одном компьютере в сети `multi-browser-network`:

```bash
# browser repository
docker compose -f compose.yaml -f compose.local-integration.yaml up -d --build

# Если внешний proxy временно недоступен — только для локального smoke:
docker compose -f compose.yaml -f compose.local-integration.yaml -f compose.local-direct-proxy.yaml up -d --build

# otziv repository
docker compose --env-file F:/Works/Projects/.otziv/env/prod-local.env \
  -f compose.prod-local.yaml -f compose.multibrowser-local.yaml up -d --build
```

В обоих env задайте одинаковый API key. Локальный overlay otziv по умолчанию использует `MULTIBROWSER_CONNECTION_MODE=DIRECT` и `http://multibrowser-app:8081`; при появлении рабочего sticky proxy переключите режим на `PROXY` и задайте `MULTIBROWSER_PROXY_URL`. Для контейнерного browser backend `PROFILES_HOST_DIR` должен быть абсолютным Linux/WSL-путём, доступным Docker daemon и смонтированным по тому же пути.

На Docker Desktop Windows browser API также можно запускать контейнерно. Задайте `PROFILES_HOST_DIR` через внутренний Docker Desktop путь вида `/run/desktop/mnt/host/f/path/to/project/profiles`, `DOCKER_GID=0`, а `otziv` запускайте с `compose.multibrowser-local.yaml`: тогда оба backend находятся в `multi-browser-network`. `compose.local-direct-proxy.yaml` предназначен только для smoke и использует публичный IP компьютера; для реальной проверки замените его рабочим provider proxy. noVNC использует случайные loopback-порты; web/mobile разрешают cross-port только когда сама страница также открыта с loopback.

## Proxy

Есть два явных режима. `DIRECT` не использует proxy и очищает ранее сохранённую привязку; browser-сервис должен быть запущен с `BROWSER_ALLOW_DIRECT=true`. `PROXY` требует непустой endpoint, проверяет доступность и стабильность IP и работает fail-closed: при отказе заданного proxy прямого выхода нет.

Поддерживаются `socks5://`, `http://` и `https://` endpoints, включая credentials; предпочтительно передавать otziv адрес внутреннего gateway без credentials. Production по умолчанию использует `PROXY`, локальный Compose — `DIRECT`.

Прокси-учётные данные хранятся browser-сервисом отдельно и шифруются. URL с userinfo и пароль нельзя помещать в Compose command, логи, issue или Git. Ранее раскрытый credential необходимо отозвать у провайдера и заменить.

## Быстрая проверка

```bash
curl https://browser.example.com/actuator/health
curl -i https://browser.example.com/api/v1/integration/profiles/available-mobile-devices
curl -H "X-API-Key: $MULTIBROWSER_API_KEY" \
  https://browser.example.com/api/v1/integration/profiles/available-mobile-devices
```

Ожидается: health `200`, запрос без ключа `401`, запрос с ключом `200`. Затем откройте браузер бота из otziv: интерфейс должен показать VNC password, noVNC — запросить его, а закрытие/heartbeat — завершать один и тот же provider profile.

Не считайте локальную Compose-проверку заменой runtime smoke: перед production отдельно проверьте реальный Docker socket, создание Chromium-контейнера, WebSocket через оба reverse proxy и выход через proxy по внешнему IP.
