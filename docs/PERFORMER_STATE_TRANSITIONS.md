# Переходы назначения и предложения исполнителю

Рабочая таблица P12.1 описывает текущие `PerformerAssignmentService`,
`AdminPerformerService` и `PerformerNotificationService`. Это отдельная модель
от этапов offline maintenance в `LEGACY_QUEUE_MAINTENANCE.md`.

Все переходы ниже выполняются в транзакции под каноническими блокировками
**Order → Assignment → Offer → PerformerProfile**. Начальный обычный запрос ID
не даёт права на запись: владелец повторно читает заблокированную сущность.
Если отзыв уже заблокирован общим сценарием заказа, сохраняется тот же Order owner.
API и Telegram используют одни внутренние accept/decline переходы; scheduler
сначала выбирает IDs, затем отдельно проверяет каждый текущий агрегат под lock.

| Действие / вход | Ожидаемое состояние | Результат | Счётчики и побочные записи | Повтор / конкуренция |
|---|---|---|---|---|
| Создание задания `createAssignmentIfEligible` | Подходящий order/review; assignment для review отсутствует | Assignment `CREATED` | Снимок текста и суммы; без статистики исполнителя | Повтор не создаёт второе assignment |
| Предложение `offerNext` | Assignment `CREATED`; текущего `OFFERED` нет; есть подходящий ACTIVE performer | Assignment `OFFERING`; Offer `OFFERED`, delivery `PENDING` | Durable OFFER intent в той же TX; статистика не меняется | Повтор видит активное предложение. DB unique active_assignment_id запрещает второй `OFFERED` |
| Восстановление указателя активного предложения | Assignment `CREATED`, найден прежний `OFFERED` | Assignment `OFFERING`, новое предложение не создаётся | Без статистики и новой отправки | Согласует уже существующую связь |
| Кандидаты исчерпаны | Assignment `CREATED`, применимое условие исчерпания | Assignment `REJECTED` с причиной | Не начисляет вину произвольному исполнителю | Terminal assignment не выбирается обычным offer scheduler |
| Accept через кабинет / Telegram | Offer `OFFERED`; Assignment `OFFERING`; ACTIVE назначенный performer; подтверждённый deadline не истёк | Offer `ACCEPTED`; Assignment `ACCEPTED`, performer и acceptedAt | ACCEPTED intent; другие прежние `OFFERED` того assignment становятся `SKIPPED`; counters не увеличиваются | Повтор accept — 409. Если первым committed decline/expire, принять уже нельзя |
| Decline через кабинет / Telegram | Принадлежность и ACTIVE performer; Offer `OFFERED` | Offer `DECLINED`; Assignment `OFFERING → CREATED` | respondedAt, причина; без штрафных counters | Если offer уже обработан, намеренный no-op. Поздний decline не отменяет committed accept |
| Expire scheduler | Offer `OFFERED`; `expiresAt ≤ now`; delivery `DELIVERED`, `LEGACY_CONFIRMED` или `LEGACY_UNKNOWN` | Offer `EXPIRED`; Assignment `OFFERING → CREATED` | `expiredOfferCount +1` только для DELIVERED/LEGACY_CONFIRMED. Для LEGACY_UNKNOWN — без штрафа | Второй scheduler видит terminal offer и не увеличивает counter |
| Walk `markWalked` | Assignment `ACCEPTED`, принадлежащий ACTIVE performer | Assignment `WAITING_PUBLICATION` | walkedAt, publishAvailableAt, publicationGeneration +1; review.vigul; WALK evidence и READY intent | Повтор — 409; вся generation/intent запись откатывается вместе |
| Publish `markPublished` | Assignment `WAITING_PUBLICATION`; выдержка истекла; непустой итоговый текст | Assignment `PUBLISHED_CLAIMED` | Текст/URL, отметки review publication, external check PENDING, PUBLISH evidence; без completedCount | Повтор из другого состояния — 409 |
| Проблема `reportProblem` | Assignment `ACCEPTED`, `WAITING_PUBLICATION` или `PUBLISHED_CLAIMED` | Assignment `REJECTED` | Причина, PROBLEM evidence, `cancelledCount +1` | Повтор из REJECTED отклоняется, counter не увеличивается снова |
| Verify `markVerifiedByReview` | Assignment `PUBLISHED_CLAIMED` | Assignment `VERIFIED` | APPROVED payout, если ещё отсутствует и есть performer; тогда `completedCount +1`. При всех VERIFIED/PAID заданиях — попытка перевода заказа в «Опубликовано» | VERIFIED/PAID или неподходящая фаза — no-op; повтор payout не создаётся |
| Ручной admin verify | Review существует; assignment не PAID | При необходимости перевод в PUBLISHED_CLAIMED, затем общий verify | Manager note, прежний payout/counter guard. Это явный административный override, включая прежние rejected/created состояния | PAID — 409; VERIFIED не удваивает payout. Этот вход не изображается обычной публикацией исполнителя |
| Подтверждение доставки предложения | Текущий fenced intent; положительный подтверждённый Telegram messageId | Delivery `DELIVERED`; business Offer status сохраняется | deliveredAt устанавливается один раз; TTL = deliveredAt + responseTtlMinutes | Поздний неподходящий claim не переписывает outcome/TTL |
| Timeout / истёкший sender claim | Возможная отправка без надёжного receipt | Intent / delivery `UNKNOWN` | Без штрафов и автоматического retry | Только сверка; elapsed timeout не доказывает отсутствие сообщения |
| Оператор CANCEL delivery | UNKNOWN/BLOCKED/LEGACY_UNKNOWN intent и актуальное состояние | Intent `CANCELLED`; ещё OFFERED offer → SKIPPED, OFFERING assignment → CREATED | Actor/evidence audit; без штрафа | Fenced решение не перезаписывает уже изменённый intent |

`WALKED`, `PAID`, `CANCELLED` существуют как значения модели и исторические/terminal
состояния, но перечисленные владельцы не создают новые обычные переходы в них.
В частности, нынешний `markWalked` переходит сразу в WAITING_PUBLICATION; таблица
не выдумывает промежуточную фазу. `failedCheckCount` этими переходами не увеличивается.
Загрузка screenshot изменяет evidence, а не фазу; paid/rejected/cancelled имеют
существующие ограничения пользовательского upload.

При PENDING/UNKNOWN доставки кабинет остаётся самостоятельным авторизованным
каналом принятия предложения. Предварительное expiresAt, созданное вместе с offer,
не запускает штрафной deadline до подтверждения. Для старой истории действуют
отдельные консервативные правила rollout, без восстановления выдуманного deliveredAt.

## Причинные проверки

`PerformerAssignmentConcurrencyMySqlIntegrationTest` проверяет double accept,
два expiry scheduler, accept на истёкшем deadline и оба порядка accept/decline.
В последнем случае первый участник удерживает настоящий canonical lock;
`performance_schema.data_lock_waits` подтверждает ожидание второго до разрешения
commit barrier. Проверяются Offer + Assignment, один/нулевой вызов accepted notification owner,
отсутствие лишних expired/completed/cancelled counters. Случайные sleep не определяют
победителя. Generation/intent rollback и конкуренция readiness проверяются отдельно
в том же JPA/MySQL suite и `PerformerNotificationMySqlIntegrationTest`.

Результат конкретного запуска фиксируется в evidence; наличие таблицы не является
заявлением о выполненном production conflict review или drain старого fleet.
