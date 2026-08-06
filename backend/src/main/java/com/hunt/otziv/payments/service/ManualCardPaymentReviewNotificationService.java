package com.hunt.otziv.payments.service;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManualCardPaymentReviewNotificationService {

    public static final String REMINDER_SOURCE = "MANAGER_REPORTED_MANUAL_CARD_PAYMENT";

    private final UserService userService;
    private final PersonalReminderService personalReminderService;
    private final TelegramService telegramService;

    public void notifyAfterCommit(ReviewRequest request) {
        if (request == null || request.orderId() == null) {
            return;
        }
        Runnable notification = () -> notifyNow(request);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        notification.run();
                    }
                }
            });
            return;
        }
        notification.run();
    }

    private void notifyNow(ReviewRequest request) {
        String text = notificationText(request);
        Long sourceId = request.evidenceLinkId() == null ? request.orderId() : request.evidenceLinkId();
        for (User recipient : recipients().values()) {
            try {
                if (!personalReminderService.hasOpenSystemReminder(recipient, REMINDER_SOURCE, sourceId)) {
                    personalReminderService.createSystemReminderDueNow(
                            recipient,
                            "Проверить ручную оплату заказа №" + request.orderId(),
                            limit(text, 1000),
                            REMINDER_SOURCE,
                            sourceId,
                            request.orderId()
                    );
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось создать напоминание о ручной оплате orderId={}, userId={}",
                        request.orderId(),
                        recipient.getId(),
                        exception
                );
            }
            if (recipient.getTelegramChatId() == null) {
                continue;
            }
            try {
                telegramService.sendMessage(recipient.getTelegramChatId(), text);
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось отправить Telegram о ручной оплате orderId={}, userId={}",
                        request.orderId(),
                        recipient.getId(),
                        exception
                );
            }
        }
    }

    private Map<Long, User> recipients() {
        Map<Long, User> recipients = new LinkedHashMap<>();
        addRecipients(recipients, userService.getAllOwners("ROLE_OWNER"));
        addRecipients(recipients, userService.getAllOwners("ROLE_ADMIN"));
        return recipients;
    }

    private void addRecipients(Map<Long, User> recipients, List<User> users) {
        if (users == null) {
            return;
        }
        users.stream()
                .filter(user -> user != null && user.getId() != null && user.isActive())
                .forEach(user -> recipients.putIfAbsent(user.getId(), user));
    }

    private String notificationText(ReviewRequest request) {
        String company = clean(request.companyTitle()).isBlank()
                ? "не указана"
                : clean(request.companyTitle());
        String providerStatus = clean(request.providerStatus()).isBlank()
                ? clean(request.bankLinkStatus())
                : clean(request.providerStatus());
        return "Менеджер отметил заказ оплаченным переводом на мобильный банк."
                + "\nЗаказ: №" + request.orderId()
                + "\nКомпания: " + company
                + "\nСумма: " + rubles(request.amountKopecks()) + " ₽"
                + "\nМенеджер: " + valueOrDefault(request.actor(), "не указан")
                + "\nПричина: " + valueOrDefault(request.reason(), "не указана")
                + "\nT-Bank: ссылка №" + request.bankLinkId()
                + ", статус " + valueOrDefault(providerStatus, "неизвестен")
                + paymentIdSuffix(request.paymentId())
                + "\nОнлайн-платеж проверен и безопасно закрыт системой. Проверьте поступление в выписке.";
    }

    private String paymentIdSuffix(String paymentId) {
        String value = clean(paymentId);
        if (value.isBlank()) {
            return "";
        }
        int visibleFrom = Math.max(0, value.length() - 4);
        return ", PaymentId …" + value.substring(visibleFrom);
    }

    private String rubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2).stripTrailingZeros().toPlainString();
    }

    private String valueOrDefault(String value, String fallback) {
        String clean = clean(value);
        return clean.isBlank() ? fallback : clean;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record ReviewRequest(
            Long evidenceLinkId,
            Long orderId,
            String companyTitle,
            long amountKopecks,
            String actor,
            String reason,
            Long bankLinkId,
            String paymentId,
            String bankLinkStatus,
            String providerStatus
    ) {
    }
}
