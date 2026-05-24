# Otziv Mobile

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
