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

## Проверка JWT и протокол P11

Backend уже отклоняет присутствующий несовпадающий `auth_epoch`/`authEpoch`.
`auth-epoch-claim-required=false` разрешает только отсутствие claim; malformed
claim также отклоняется. Существующие active/sub/roles checks сохраняются.
Service accounts исключаются только точным client allowlist вместе с
соответствующим `service-account-{id}` username.

Mapper текущего user attribute в `auth_epoch` **не добавляется**: refresh старой
сессии мог бы получить новое поколение. Подписанный `sid` связывается с epoch
один раз в `auth_session_bindings`. Записи immutable и сохраняются как
tombstones без TTL cleanup до доказанного полного offline/remember-me horizon.

`otziv.security.session-revocation-mode`: `off` (default), `shadow`, `enforce`.
Shadow читает issuer, но не пишет binding и не ослабляет прежние отказы.
Enforce требует live introspection на каждом запросе, точный user/sid/client,
online или offline session и password credential createdDate относительно
исходного session start. Offline client UUID берётся из grants конкретного
пользователя, а не из clientId/audience; дополнительных client-management прав
не требуется. Удаление локально необходимой роли в issuer запрещает запрос;
добавленная в issuer роль не повышает локальные права.

В Keycloak 26.2.5 reset меняет password createdDate, rehash сохраняет его.
Это позволяет отклонять старую сессию после внешнего reset, даже если Keycloak
сохраняет её. Session start имеет секундную точность; ambiguous same-second
boundary отклоняется и требует нового login после следующей секунды. Missing
password metadata, federation/passwordless или неизвестный формат сессии
считаются unsupported в strict mode. `offline_access` не удаляется.

Успешные live ответы не кэшируются. Adapter имеет connect timeout 3s, read
timeout 5s; authority bulkhead допускает 8 параллельных проверок. Проверка делает
несколько HTTP calls: latency/capacity должны измеряться до enforcement.
Недоступность issuer/БД возвращает 503 + Retry-After, чтобы не очищать mobile
session при временной сети. Public optional-auth paths продолжаются анонимно.

## Durable mutations и reconciliation

V1_10_294 создаёт additive bindings, `auth_security_mutations` и
`auth_revocation_sessions`. Epoch increment, push revoke и mutation атомарны.
Password reserve коммитится **до** remote reset; пароль не хранится в intent.
Timeout сохраняет `PASSWORD_UNKNOWN`; abandoned `PASSWORD_REQUESTED` через
две минуты лишь классифицируется как UNKNOWN. Это не подтверждение исхода,
не success и не разрешение автоматического повтора пароля.

Reconciler сохраняет snapshot конкретных online/offline sid, затем вне
транзакции удаляет именно их. Idempotent DELETE допускает повтор/404. Targets
immutable: late retry не вызывает logout-all и не удаляет последующий новый
sid. Только подтверждённое отсутствие всех targets завершает mutation. Пока
есть pending, strict admission закрыт. После live lookup под users lock
повторно проверяются active/sub/epoch/roles/pending, закрывая reset/bind race.

Включить `otziv.security.session-revocation-dispatch-enabled=true` вместе с
durable writers, даже если admission пока off/shadow; иначе pending потребует
ручного reconciliation. Password endpoint синхронно доводит свой отзыв до
COMPLETE перед успешным ответом.

ADMIN API `/api/admin/security/session-revocations`:

- GET: ограниченный pending список без JWT/password/session payload.
- POST `/{operation}/reconcile`: повтор только scoped session deletion.
- POST `/{operation}/resolve-password-outcome` с `verifiedExternalOutcome`:
  после внешней проверки результата оператор переводит PASSWORD_UNKNOWN в
  обязательный logout. Actor и evidence сохраняются. Не использовать, пока
  remote reset ещё может выполняться; elapsed timeout не доказывает исход.
- POST `/bootstrap/{userId}`: явный epoch increment, push revoke и scoped
  online/offline logout существующего пользователя. Массового logout при
  применении миграции нет.

Внешние KC account/reset/admin изменения проверяются по live state, credential
fence и introspection. Внешний «отзыв всех сессий» должен включать online **и**
offline copies. Переключение enabled/roles туда и обратно само по себе не
является подтверждённым session revoke: текущая проверка не реконструирует
пропущенную историю промежуточных состояний. Для такой гарантии внешнему
процессу нужен issuer event protocol. Он реализован отдельным PostgreSQL journal,
глобальным Keycloak listener и immutable session note в
[ISSUER_SECURITY_GENERATION.md](ISSUER_SECURITY_GENERATION.md).
Эта гарантия действует после установки provider и активации соответствующего
backend gate; прежний live-state режим сам по себе её не обеспечивает.

## Порядок выпуска и откат

1. Подтвердить P07: восстановление обеих БД и key material. Применить expand
   миграцию; выпустить durable writers с dispatcher и admission off/shadow.
2. Прогнать opt-in `KeycloakSessionProtocolRuntimeIntegrationTest` на pinned
   локальном issuer; затем old mobile/new backend, web, public links,
   `/api/me`, push revoke, outage503 и concurrency. Unit suite не заменяет
   клиентскую runtime matrix. Тест читает ro local env, создаёт/удаляет только
   случайные disposable user/client и не печатает токены/пароли.
3. Проверить shadow coverage, unsupported credentials, actual scopes/basic/sub/sid,
   service allowlist, pending backlog, latency и capacity. Не включать
   auth-epoch-claim-required автоматически.
4. Drain старых auth readers/writers. В согласованном окне выполнить bootstrap
   существующего охвата и дождаться COMPLETE; исторические missing-claim
   сессии нельзя считать backfilled без этого шага.
5. Включить enforce с `session-revocation-cutover-confirmed=true` и dispatcher;
   startup откажет без обоих gates. Mixed fleet с bypass-кодом не защищён.
   Уже принятые до revocation запросы не прерываются задним числом.
6. Откат сохраняет epoch, bindings, mutations и targets. Не уменьшать epoch,
   не удалять tombstones и не делать down-migration. Возврат admission в
   shadow/off вновь открывает прежнее missing-claim окно риска.

## Проверка rollout

Для наблюдения доступны gauges `otziv.security.session_revocations.pending`,
`unknown`, `password_in_flight`, `dispatchable`, `oldest_pending_seconds` и
`oldest_unknown_seconds` с тем же префиксом. Снимок обновляется каждые 30 секунд
одним агрегатным SQL по незавершённым mutations; scrape не читает сессии и
immutable bindings. Проверять вместе с `snapshot_available`,
`snapshot_age_seconds` и counter `snapshot.errors`: при сбое обновления
сохраняется последний успешный снимок, его возраст растёт. Counter
`dispatch.reconciled_attempts` считает успешные вызовы reconcile, а не уникальные
завершённые mutations; `dispatch.errors` отражает ошибки обхода и обработки.
Метки с user/session/operation ID, токенами или payload отсутствуют. Рост
`unknown` требует проверки результата смены пароля по описанной выше процедуре;
перезапуск dispatcher не подтверждает удалённый результат.

Локальное evidence 2026-09-07: Java26 scoped suite — 73 PASS (05:28:16 UTC),
включая MySQL rollback/race/UNKNOWN и миграционный active-offer guard.
`KeycloakSessionProtocolRuntimeIntegrationTest` — PASS на Keycloak 26.2.5
(05:27:59 UTC): normal online, offline refresh, external admin password reset,
старый/обновлённый старой сессией JWT, actual password coordinator,
durable scoped revocation и новый login. Один live check — 172 ms; это не
capacity benchmark. Fixture использует реальный Keycloak, настоящие JDBC
state/transactions и минимальный users repository adapter, не полный HTTP app
и не установленный мобильный клиент. Production rollout/клиентская матрица
этим результатом не подменяются.

Issuer implementation sources:
[PasswordCredentialProvider 26.2.5](https://github.com/keycloak/keycloak/blob/26.2.5/services/src/main/java/org/keycloak/credential/PasswordCredentialProvider.java),
[UserResource 26.2.5](https://github.com/keycloak/keycloak/blob/26.2.5/services/src/main/java/org/keycloak/services/resources/admin/UserResource.java),
[AccessTokenIntrospectionProvider 26.2.5](https://github.com/keycloak/keycloak/blob/26.2.5/services/src/main/java/org/keycloak/protocol/oidc/AccessTokenIntrospectionProvider.java).

- повторная регистрация ранее отозванного/чужого FCM token переносит его
  текущему аутентифицированному пользователю;
- token со старым epoch не попадает в выборку отправителя;
- одиночный revoke нельзя применить к token другого пользователя;
- повторный revoke не меняет состояние;
- `/api/me` работает для связанного пользователя и service principal;
- password/deactivation/role change увеличивают epoch и отзывают push.
