# История отзыва сессий на стороне Keycloak

P11, дополнение к [локальным эпохам и push revoke](AUTH_EPOCH_PUSH_REVOKE_ROLLOUT.md).
Реализовано для **Keycloak 26.7.3 + PostgreSQL**. Протокол закрывает возврат доступа
старой сессии после внешнего изменения `enabled/roles` туда и обратно.

## Источник истины

Provider `infrastructure/keycloak/security-generation` добавляет собственную
Liquibase-таблицу `OTZIV_SEC_GENERATION` в PostgreSQL Keycloak. Счётчики realm и
пользователя монотонны; строки не очищаются при удалении сессии/пользователя.
Это отдельный журнал, а не редактируемый атрибут пользователя.

Перед проверкой пароля authenticator захватывает shared lock realm, затем
exclusive lock пользователя, до конца транзакции Keycloak. Успешный вход сохраняет
`v1:<realm generation>:<user generation>` в user session note
`otziv.security.generation.v1`. Штатный UserSessionNoteMapper выдаёт claim
`otziv_session_generation`. Refresh/offline refresh сохраняют исходное значение;
старые сессии не получают текущую эпоху задним числом.

Глобальный listener обрабатывает успешные password/credential events и admin
изменения пользователя, realm, ролей, групп, клиентов и authentication configuration.
Он работает независимо от выбранного списка listeners и хранения admin events.
Security mutation и increment выполняются в одной PostgreSQL транзакции. При
ошибке listener выставляет rollback-only: обычное исключение Keycloak перехватывает.
Exact `DELETE sessions/{old sid}` не повышает эпоху пользователя, сохраняя отдельный
новый вход при поздней обработке старой сессии.

Realm-wide изменения консервативно отзывают все старые сессии realm, включая
изменения конфигурации активации механизма. Переполнение/повреждение счётчика
приводит к отказу, а не к обнулению.

## Проверка backend

При `OTZIV_SECURITY_ISSUER_GENERATION_REQUIRED=true` backend сравнивает неизменный
claim с текущим значением из `/realms/{realm}/otziv-security/generation/{subject}`.
Ресурс требует service account с `realm-management:view-users` или `manage-users`.
Обычный пользователь получает 403. Кэша успешных сравнений нет. Отсутствующий claim
отказывает во входе; недоступный журнал переводит проверку в временный отказ.
Это дополняет прежние проверки `sid`, текущего пользователя/пароля/ролей и local epoch.

Realm attribute `otziv.security.generation.enabled=true` активирует authority
endpoint. Иначе он отвечает 503; журнал продолжает работать. Backend режим
`enforce` дополнительно требует новый флаг вместе с прежними `cutoverConfirmed`
и включённой обработкой durable revocation.

## Установка

1. Завершить paired recovery MySQL + Keycloak PostgreSQL + objects + secrets.
   Журнал и signing keys должны относиться к одной точке восстановления.
2. Из `backend` собрать provider Java 21+:
   `mvnw -f ../infrastructure/keycloak/security-generation/pom.xml verify`.
   Собрать его Dockerfile, зафиксировать image digest и выполнить `container-proof.mjs`.
3. Выпустить provider на все issuer nodes при выключенном backend enforcement.
   Production/local Compose поддерживают `OTZIV_KEYCLOAK_IMAGE`; по умолчанию выбран
   прежний pinned Keycloak. Смешанный issuer fleet не активировать.
4. `provision.mjs <issuer URL> <realm> <protected-output.json>` читает план; admin token
   передаётся только через `OTZIV_KEYCLOAK_ADMIN_TOKEN`. Обязательный список
   `OTZIV_KEYCLOAK_PROTECTED_CLIENT_IDS` содержит точные client IDs приложения через
   запятую; встроенные account/admin clients не включать. Отсутствующий, выключенный
   или неинтерактивный клиент останавливает установку. Последний аргумент `planHash`
   применяет план при совпадении текущей конфигурации. Копируются реальные nested
   browser/direct grant flows и client overrides; сохраняются OTP, условия,
   конфигурация и порядок. План связывает полные execution DTO, значения nested
   authenticator configs и direct/default/optional mapper inputs; в отчёт попадают
   только их хеши. Унаследованный writer того же claim требует разбора до первой
   записи. Меняются только password authenticators. Активные старые
   flows не удаляются. Незавершённая чужая копия требует отдельного разбора.
   Для длительной установки можно передать `OTZIV_KEYCLOAK_ADMIN_REFRESH_TOKEN`,
   `OTZIV_KEYCLOAK_ADMIN_REALM` (по умолчанию `master`) и
   `OTZIV_KEYCLOAK_ADMIN_CLIENT_ID` (по умолчанию `admin-cli`). Адаптер обновляет
   токен только после 401 и один раз повторяет отклонённый запрос с тем же телом.
   Новый refresh token остаётся в памяти. Повторный 401, ошибка обновления, 403,
   сбой сети или неопределённый результат записи не запускают повторную мутацию.
5. Проверить реальные web/mobile входы, refresh/offline, SSO и backend authority.
   Passwordless/IdP/federation отдельно доказывают эквивалентный anchor либо остаются
   запрещёнными в enforce. Отсутствие claim нельзя компенсировать чтением текущего
   атрибута пользователя.
6. Завершить прежний bootstrap/drain и наблюдение shadow, переключить весь backend
   fleet. Старым сессиям без anchor нужен новый вход. Сборка JAR сама enforcement
   в production не включает.

## Восстановление и ограничения

При возврате версии сохранить journal/provider/guards либо выполнить проверенное
полное восстановление с отзывом восстановленных сессий и сменой signing keys
**до** открытия трафика. Нельзя уменьшать или пересоздавать счётчики ради входа.
Образ без provider вместе с enforcement закрывает доступ.

Container proof использует настоящий Keycloak/PostgreSQL: production provisioning,
browser PKCE/direct grant, role remove/restore, enabled false/true, refresh/offline,
scoped sid deletion, restart, listener settings и PostgreSQL trigger failure с
откатом реального пользователя. Backend unit tests проверяют authority/startup gate.
Итоговые хеши и результаты указаны в реестре; synthetic fixture не заменяет shadow.

Модель доверяет администраторам PostgreSQL, signing keys и provider binaries.
Прямые SQL изменения/откат журнала вне recovery protocol не поддерживаются.
SPI закреплён на указанной версии Keycloak; её обновление требует повторного
интеграционного набора. Native/IdP/passwordless сценарии не объявлены проверенными.

Состав и проверка hardened image описаны в
[README provider image](../infrastructure/keycloak/security-generation/README.md).
Исходный образ 26.2.5 не является кандидатом выпуска: его скан выявил исправляемые
уязвимости. Результаты старых протокольных проверок сохраняются как история,
но приёмка использует точный digest окончательного образа.

Исходные контракты, использованные при разработке и повторно проверенные интеграционным
набором на 26.7.3: [EventListenerProvider](https://github.com/keycloak/keycloak/blob/26.2.5/server-spi-private/src/main/java/org/keycloak/events/EventListenerProvider.java),
[AdminEventBuilder](https://github.com/keycloak/keycloak/blob/26.2.5/services/src/main/java/org/keycloak/services/resources/admin/AdminEventBuilder.java),
[UserSessionNoteMapper](https://github.com/keycloak/keycloak/blob/26.2.5/services/src/main/java/org/keycloak/protocol/oidc/mappers/UserSessionNoteMapper.java).
