# Outreach вынесен в `peoples`

Бизнес-процесс personal outreach перенесён в `F:\\Работа\\Java\\Проекты\\peoples\\peoples` и описан там в `docs/OUTREACH.md`. В `otziv` больше не запускаются проверка `lastSeen`, рекламная рассылка и обработка личных ответов.

В `otziv` остаются только:

- `GroupReplyService` и `/webhook/whatsapp-group-reply` — групповой поток менеджеров и автоответчика;
- общий Node WhatsApp gateway, который `peoples` использует как remote controller;
- отключённый по умолчанию `/internal/outreach/v1/**` — защищённый bridge к существующим лидам, шаблонам, WhatsApp gateway и уведомлениям.

Локальные `outreach-module`, `backend/.../outreach/host` и Maven reactor удалены. Backend снова собирается самостоятельно из `backend/pom.xml`.

## Граница приложений

```text
WhatsApp group reply -> otziv -> GroupReplyService

WhatsApp personal reply -> peoples -> outreach workflow
                                      |
schedule/manual start ---> peoples ---+-> protected otziv bridge
```

Bridge не содержит расписаний и решений о рассылке. Он включается только парой `OUTREACH_BRIDGE_ENABLED=true` и `OUTREACH_BRIDGE_SHARED_SECRET` длиной не менее 32 символов.

Node gateway отправляет личные ответы в `peoples`, только когда заданы `OUTREACH_WEBHOOK_ENABLED=true`, `OUTREACH_SERVER_URL` и отдельный `OUTREACH_WEBHOOK_SECRET`. Для выделенного personal controller используется `WHATSAPP_GROUP_WEBHOOK_ENABLED=false`.

В remote-режиме gateway получает `WHATSAPP_BROWSER_URL`, `WHATSAPP_BROWSER_PROFILE_ID` и `WHATSAPP_REMOTE_BROWSER_REQUIRED=true`, подключается по CDP к постоянному профилю `peoples` и при завершении только отсоединяется от Chromium. Профиль, proxy, fingerprint и WhatsApp-сессия принадлежат `peoples`.

Перед включением рассылки вызовите защищённый `GET /api/v1/admin/outreach/preflight` в `peoples`. Продолжать запуск можно только при HTTP 200, `ready=true` и пустом `issues`.
