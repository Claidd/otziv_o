package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.notification_media.api.StaffMediaSignal;

import com.hunt.otziv.config.settings.api.ContextualMediaSettings;
import com.hunt.otziv.notification_media.model.NotificationRecipientType;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContextualStaffMediaService {
    private final ContextualMediaFacts facts;
    private final NotificationMediaDeliveryService delivery;
    private final ThematicNotificationDispatchStore dispatch;
    private final ContextualMediaSettings settings;

    @Async("contextualMediaExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSignal(StaffMediaSignal signal) {
        try {
            if (!settings.enabled()) return;
            var recipient = facts.recipient(signal.userId());
            if (recipient.isEmpty()) return;
            NotificationMediaEventCatalog event;
            if ("SITE_ERROR".equals(signal.action())) {
                event = NotificationMediaEventCatalog.WORKER_SITE_ERROR;
            } else if ("NETWORK_BLOCKED".equals(signal.action())) {
                event = facts.networkBlocked(signal.userId()) ? NotificationMediaEventCatalog.WORKER_NETWORK_BLOCKED : null;
            } else if ("MANAGER_CONTEXT".equals(signal.action())) {
                event = facts.unansweredClientChat(signal.userId())
                        ? NotificationMediaEventCatalog.MANAGER_CLIENT_CHAT_PENDING
                        : facts.unpaidOrder(signal.userId()) ? NotificationMediaEventCatalog.MANAGER_PAYMENT_PENDING : null;
            } else {
                var card = facts.ownedCard(signal.userId(), signal.entityType(), signal.entityId());
                event = ContextualMediaPolicy.resolve(signal, card,
                        "REVIEW_PUBLISH".equals(signal.action()) ? facts.publicationsToday(signal.userId()) : 0);
            }
            if (event == null) return;
            Long chat = number(recipient.get(event.recipientType() == NotificationRecipientType.MANAGER
                    ? "audit_telegram_group_chat_id" : "worker_telegram_group_chat_id"));
            if (chat == null || chat == 0) chat = number(recipient.get("telegram_chat_id"));
            if (chat == null || chat == 0) return;
            LocalDate date = LocalDate.now(ZoneId.of("Asia/Irkutsk"));
            int maximum = settings.maxPerDay();
            if (!dispatch.claim(event.code(), signal.userId(), date, maximum)) return;
            // An ambiguous transport failure remains claimed: never replay a possibly delivered photo.
            boolean sent = delivery.sendMediaOnly(event.code(), chat, signal.userId(), caption(event, signal), null);
            if (sent) dispatch.markSent(event.code(), signal.userId(), date);
            else dispatch.release(event.code(), signal.userId(), date);
        } catch (RuntimeException exception) {
            log.warn("Contextual media skipped action={}, userId={}: {}", signal.action(), signal.userId(), exception.getMessage());
        }
    }

    static String caption(NotificationMediaEventCatalog event, StaffMediaSignal signal) {
        String reference = signal.entityId() == null ? "" : "\nКарточка #" + signal.entityId();
        return switch (event) {
            case WORKER_NAGUL_DONE -> "Выгул отмечен выполненным ✅" + reference;
            case WORKER_PUBLICATION_DONE -> "Публикация отмечена выполненной ✅" + reference;
            case WORKER_THREE_PUBLICATIONS -> "Сегодня отмечены три разные публикации ✅";
            case WORKER_TEXT_SAVED -> "Текст отзыва сохранён ✅" + reference;
            case WORKER_RATING_DONE -> "Задача по исправлению оценки закрыта ✅" + reference;
            case WORKER_FRESH_ACCOUNT_GUIDE -> "Свежий аккаунт: проверьте оформление профиля и выполните выгул перед публикацией." + reference;
            case WORKER_NAGUL_GUIDE -> "Памятка перед выгулом: работайте по карточке и соблюдайте порядок действий." + reference;
            case WORKER_TEXT_GUIDE -> "Памятка при подготовке текста: проверьте уникальность, конкретику и соответствие фото." + reference;
            case WORKER_TEXT_PENDING -> "Текст пустой. Заполните его перед сохранением." + reference;
            case WORKER_PUBLICATION_PENDING -> "Карточка ожидает публикации. Проверьте текст и указания перед отправкой." + reference;
            case WORKER_RECOVERY_GUIDE -> "Открыта задача восстановления. Следуйте указаниям именно этой карточки." + reference;
            case WORKER_RECOVERY_FINISH -> "Изменения сохранены. Если публикация уже выполнена, завершите задачу кнопкой «Восстановил»." + reference;
            case WORKER_ACCOUNT_CHANGED -> "Аккаунт изменён. Проверьте текст перед дальнейшей работой." + reference;
            case WORKER_UNSAVED_CHANGES -> "Редактор закрыт с несохранёнными изменениями. Вернитесь к карточке и сохраните нужные правки." + reference;
            case WORKER_ACCOUNT_LOGIN_GUIDE -> "Пароль рабочего аккаунта скопирован. Если при входе MAX просит авторизацию, пригодится эта памятка." + reference;
            case WORKER_NETWORK_BLOCKED -> "Доступ из немобильной сети заблокирован. Переключитесь на разрешённое подключение.";
            case WORKER_SITE_ERROR -> "При работе с разделом возникла серверная ошибка. Сообщите, на каком шаге она появилась.";
            case MANAGER_CLIENT_CHAT_PENDING -> "В сегодняшнем контроле есть клиентский чат без ответа. Проверьте его и ответьте клиенту.";
            case MANAGER_PAYMENT_PENDING -> "Есть заказ в статусе «Оплата». Проверьте получение оплаты и при необходимости свяжитесь с клиентом.";
            default -> event.label() + reference;
        };
    }

    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
}
