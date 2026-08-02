# Auth epoch and mobile push revoke rollout

## Что включено

Миграции `V1_10_166` и `V1_10_171` остаются additive. Поле `users.row_version`
сопоставлено с entity как обычное поле и намеренно не помечено `@Version`, чтобы
не менять семантику всех существующих операций с пользователями одним rollout.

`auth_epoch` используется для локального security-состояния и push:

- регистрация FCM token сохраняет текущий epoch пользователя и очищает прежнюю
  revoke-метаинформацию;
- деактивированный пользователь не может зарегистрировать новый token или
  выполнить test-send, но может отозвать уже принадлежащие ему tokens;
- доставка выбирает только активного пользователя и активный, не отозванный
  token с epoch, совпадающим с текущим `users.auth_epoch`;
- деактивация, реактивация, смена security-ролей и пароля увеличивают epoch и в
  той же транзакции отзывают активные push tokens;
- перед security-mutation строка `users` блокируется `PESSIMISTIC_WRITE`
  внутри write-транзакции, чтобы параллельные смены пароля/роли/
  активности не потеряли increment; read-only пути не блокируются;
- `/api/me` additive возвращает `localUserId`, `active` и `authEpoch`.
  Для service principal или JWT без локального пользователя значения равны
  `null`, а существующая информация о JWT остаётся доступной.

## API отзыва push

Оба endpoint требуют обычную аутентификацию:

- `POST /api/mobile/push-token/revoke` с `{ "token": "..." }` отзывает token,
  только если он принадлежит текущему пользователю;
- `POST /api/mobile/push-token/revoke-all` отзывает все активные tokens текущего
  пользователя.

Операции идемпотентны и не раскрывают, принадлежит ли переданный token другому
пользователю. Mobile client перед очисткой access token делает ограниченную по
времени best-effort попытку отзыва текущего FCM token. Ошибка сети, timeout или
старый backend не блокируют logout; при следующем login тот же FCM token будет
зарегистрирован заново.

## Optional auth публичного review

Mobile-запросы к legacy `/api/review-check/**` прикладывают Authorization только
если access token уже находится в памяти и локально не истёк. Они не запускают
refresh и не ждут Keycloak. Если сервер отвечает `401` (например, token уже
отозван), запрос один раз повторяется без Authorization, без logout и redirect.
Так публичная ссылка продолжает работать, а действующая сессия сохраняет
прежние role-возможности и internal context. `/api/review-capability/**` и
`/api/payments/public/**` всегда отправляются анонимно.

## Что намеренно не включено

Backend пока не отклоняет access token по `auth_epoch`. Существующие Keycloak
tokens не содержат гарантированного epoch claim, а client-credentials/service
accounts могут вообще не иметь локальной строки `users`. Включение строгой
проверки сейчас нарушило бы совместимость web/mobile и service principals и
добавило бы запрос к БД на каждый request.

Будущий enforcement выполняется отдельным rollout:

1. добавить protocol mapper в Keycloak и выпускать числовой epoch claim только
   для локально связанных пользователей;
2. сначала собирать shadow-метрики `missing/match/mismatch` без отказов и без
   неограниченных запросов (bounded cache или короткоживущий snapshot);
3. убедиться, что service accounts явно исключены и доля `missing/mismatch`
   ожидаема;
4. включать отказ по mismatch отдельным флагом, выключенным по умолчанию, с
   мгновенным rollback на shadow mode.

## Проверка rollout

- повторная регистрация ранее отозванного/чужого FCM token переносит его
  текущему аутентифицированному пользователю;
- token со старым epoch не попадает в выборку отправителя;
- одиночный revoke нельзя применить к token другого пользователя;
- повторный revoke не меняет состояние;
- `/api/me` работает для связанного пользователя и service principal;
- password/deactivation/role change увеличивают epoch и отзывают push.
