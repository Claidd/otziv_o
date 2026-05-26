# Компания О! Mobile

Ionic Angular + Capacitor приложение для Android и iOS. Приложение использует тот же Spring API и Keycloak realm `otziv`, но отдельный public client `otziv-mobile` с Authorization Code + PKCE.

## Web Preview

```powershell
npm install
npm start
```

Dev server работает на `http://localhost:4300` и проксирует `/api` на prod-like nginx `http://localhost:8088`.

Перед первым входом примените mobile client в уже запущенный локальный Keycloak:

```powershell
..\infrastructure\keycloak\apply-mobile-client.ps1
```

## Build

```powershell
npm run build
npm run cap:sync
```

## Android

Для Android нужен JDK 21. На этой машине установлен Temurin JDK 21:

```powershell
C:\Users\Hunt\.jdks\temurin-21\jdk-21.0.11+10
```

Подготовить окружение текущей PowerShell-сессии:

```powershell
.\scripts\android-env.ps1
```

Собрать debug APK:

```powershell
.\scripts\build-android-debug.ps1
```

APK появится в `android\app\build\outputs\apk\debug\app-debug.apk`.

Для проверки локальной APK на эмуляторе или телефоне через USB пробросьте prod-like nginx:

```powershell
adb reverse tcp:8088 tcp:8088
```

После этого приложение внутри Android сможет обращаться к `http://localhost:8088`.

Быстрый локальный запуск в уже открытом эмуляторе:

```powershell
npm run android:run:local
```

Команда собирает local/debug APK, включает `adb reverse tcp:8088 tcp:8088`, устанавливает приложение и запускает его. Перед этим локальный prod-like стек должен отвечать на `http://localhost:8088`, например после:

```powershell
..\infrastructure\scripts\local\prod-like-smoke.ps1
```

### Android Push Notifications

Обычная local/prod сборка запускается без системных push-уведомлений, чтобы APK не падал, если Firebase еще не настроен. Для push-сборки нужен файл Firebase Android config:

```text
mobile\android\app\google-services.json
```

Файл должен быть создан в Firebase Console для Android package:

```text
com.hunt.otziv
```

После добавления файла можно собрать и запустить local APK с включенными push:

```powershell
npm run android:run:local:push
```

Для production-сборки с push используйте:

```powershell
npm run cap:sync:prod:push
```

Backend принимает FCM-токен приложения через `POST /api/mobile/push-token`. Для отправки push с backend нужно включить Firebase Admin:

```properties
OTZIV_MOBILE_PUSH_FIREBASE_ENABLED=true
OTZIV_MOBILE_PUSH_FIREBASE_PROJECT_ID=...
OTZIV_MOBILE_PUSH_FIREBASE_SERVICE_ACCOUNT_PATH=/run/secrets/firebase-service-account.json
```

Проверочный endpoint для авторизованного пользователя: `POST /api/mobile/push-token/test`.

## iOS

iOS-проект создан в `ios\App`. Код и Capacitor-конфиг уже готовы, но финальная сборка iOS требует macOS и Xcode:

```powershell
npm run cap:sync
npx cap open ios
```

## Keycloak

Клиент `otziv-mobile` добавлен в локальный и production realm config. Основные redirect URI:

- `http://localhost:4300/auth/callback` для web-preview
- `otziv://auth/callback` для Android/iOS

Для локального prod-like окружения применяйте:

```powershell
..\infrastructure\keycloak\apply-mobile-client.ps1
```

После изменения существующего production realm нужно применить `infrastructure/scripts/prod/apply-keycloak-prod-settings.sh`, потому что Keycloak не переимпортирует уже созданный realm автоматически.
