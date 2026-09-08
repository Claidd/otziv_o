# ADR-002. Авторизация и жизненный цикл сессии

Статус: принято для реализации; включение production enforcement требует отдельной приёмки.
Дата: 2026-09-07. Владельцы: identity и mobile. Пункты: P02/P11/P16.

JWT подтверждает подпись и срок токена, но сам по себе не доказывает, что сессия
не была отозвана после выдачи. Локальный пользователь, issuer session и mobile
SecureStorage имеют разные моменты отказа и восстановления.

Используем `UserSessionSecurityService` и сохранённую привязку issuer session к epoch.
Проверки прав выполняются на backend; изменение роли, пароля и отзыв имеют durable
состояние и повторную проверку под блокировкой. Неизвестный результат смены пароля
не считается подтверждённым успехом или разрешением повторить внешнюю операцию.
Online/offline sessions отзываются по проверенным целевым идентификаторам.

Mobile различает пригодный токен, временную недоступность и окончательно
недействительную сессию. Временная сеть не стирает refresh token. Один shared refresh,
generation и последовательная запись SecureStorage не позволяют старому ответу
восстановить завершённую сессию. Повтор чтения после 401 ограничен; запись не
повторяется автоматически. Public capability и optional-auth endpoints сохраняют
собственный контракт доступа.

Порядок включения: recovery point → проверка issuer protocol → shadow/bootstrap
и совместимость существующих sessions → drain несовместимых writers → enforcement.
Пропущенные claims не трактуются как бесконечное разрешение; точная переходная
политика и её наблюдение находятся в runbook. Откат версии не отменяет уже
подтверждённый отзыв и не разрешает восстановить старый epoch из backup без процедуры.

Проверка: реальные Keycloak protocol tests, MySQL races, Angular runtime и signed
native upgrade/resume. Локальный Keycloak или debug APK не закрывают весь release gate.
Решение и текущая приёмка: [runbook](AUTH_EPOCH_PUSH_REVOKE_ROLLOUT.md),
[native evidence](NATIVE_ANDROID_VERIFICATION_2026-09-07.md).

Отвергнуты: только проверка JWT expiry, удаление mobile session при любом timeout,
SecurityContext как неявная замена переданного actor и требование login для всех
публичных ссылок. Пересмотр нужен при смене issuer/session claim или native storage.
