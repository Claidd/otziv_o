package com.hunt.otziv.payments.service;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManualCardPaymentReviewNotificationService {

    public static final String REMINDER_SOURCE = "MANAGER_REPORTED_MANUAL_CARD_PAYMENT";
    public static final String COMMON_INVOICE_REMINDER_SOURCE =
            "MANAGER_REPORTED_COMMON_INVOICE_CARD_PAYMENT";
    public static final String OWNER_APPROVAL_REMINDER_SOURCE =
            "OWNER_MANUAL_CARD_PAYMENT_APPROVAL";

    private final UserService userService;
    private final PersonalReminderService personalReminderService;
    private final TelegramService telegramService;
    private final PlatformTransactionManager transactionManager;
    private final PaymentIssueReminderService paymentIssueReminderService;

    public void notifyAfterCommit(ReviewRequest request) {
        if (request == null || request.orderId() == null) {
            return;
        }
        runAfterCommit(() -> notifyNow(request));
    }

    public void notifyCommonInvoiceAfterCommit(CommonInvoiceReviewRequest request) {
        if (request == null || request.invoiceId() == null) {
            return;
        }
        runAfterCommit(() -> notifyCommonInvoiceNow(request));
    }

    public void notifyOwnerApprovalAfterCommit(OwnerApprovalRequest request) {
        if (request == null || request.approvalId() == null || request.orderId() == null) {
            return;
        }
        runAfterCommit(() -> notifyOwnerApprovalNow(request));
    }

    public void closeOwnerApprovalReminders(Long approvalId) {
        if (approvalId != null) {
            personalReminderService.deleteSystemRemindersBySource(
                    OWNER_APPROVAL_REMINDER_SOURCE,
                    approvalId
            );
        }
    }

    private void runAfterCommit(Runnable notification) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        runInNewTransaction(notification);
                    }
                }
            });
            return;
        }
        notification.run();
    }

    private void runInNewTransaction(Runnable notification) {
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> notification.run());
        } catch (RuntimeException exception) {
            log.warn("Не удалось сохранить уведомление о ручной оплате после коммита", exception);
        }
    }

    private void notifyNow(ReviewRequest request) {
        String text = notificationText(request);
        String title = "Проверить ручную оплату заказа №" + request.orderId();
        Long sourceId = request.evidenceLinkId() == null ? request.orderId() : request.evidenceLinkId();
        for (User recipient : recipients().values()) {
            try {
                if (!personalReminderService.hasOpenSystemReminder(recipient, REMINDER_SOURCE, sourceId)) {
                    personalReminderService.createSystemReminderDueNow(
                            recipient,
                            title,
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
        paymentIssueReminderService.notifyOrderIssue(
                request.orderId(),
                REMINDER_SOURCE,
                sourceId,
                title,
                limit(text, 1000)
        );
    }

    private void notifyCommonInvoiceNow(CommonInvoiceReviewRequest request) {
        String text = commonInvoiceNotificationText(request);
        String title = "Проверить ручную оплату общего счета №" + request.invoiceId();
        Long sourceOrderId = request.orderIds() == null || request.orderIds().isEmpty()
                ? null
                : request.orderIds().getFirst();
        for (User recipient : recipients().values()) {
            try {
                if (!personalReminderService.hasOpenSystemReminder(
                        recipient,
                        COMMON_INVOICE_REMINDER_SOURCE,
                        request.invoiceId()
                )) {
                    personalReminderService.createSystemReminderDueNow(
                            recipient,
                            title,
                            limit(text, 1000),
                            COMMON_INVOICE_REMINDER_SOURCE,
                            request.invoiceId(),
                            sourceOrderId
                    );
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось создать напоминание о ручной оплате общего счета invoiceId={}, userId={}",
                        request.invoiceId(),
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
                        "Не удалось отправить Telegram о ручной оплате общего счета invoiceId={}, userId={}",
                        request.invoiceId(),
                        recipient.getId(),
                        exception
                );
            }
        }
        if (sourceOrderId != null) {
            paymentIssueReminderService.notifyOrderIssue(
                    sourceOrderId,
                    COMMON_INVOICE_REMINDER_SOURCE,
                    request.invoiceId(),
                    title,
                    limit(text, 1000)
            );
        }
    }

    private void notifyOwnerApprovalNow(OwnerApprovalRequest request) {
        String text = ownerApprovalNotificationText(request);
        String title = "Подтвердить поступление владельцу по заказу №" + request.orderId();
        String callbackData = OwnerManualCardPaymentApprovalCallbackData.encode(
                request.approvalId(),
                request.callbackToken()
        );
        for (User recipient : recipients().values()) {
            try {
                if (!personalReminderService.hasOpenSystemReminder(
                        recipient,
                        OWNER_APPROVAL_REMINDER_SOURCE,
                        request.approvalId()
                )) {
                    personalReminderService.createSystemReminderDueNow(
                            recipient,
                            title,
                            limit(text, 1000),
                            OWNER_APPROVAL_REMINDER_SOURCE,
                            request.approvalId(),
                            request.orderId()
                    );
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось создать запрос подтверждения владельца approvalId={}, userId={}",
                        request.approvalId(),
                        recipient.getId(),
                        exception
                );
            }
            if (recipient.getTelegramChatId() == null) {
                continue;
            }
            try {
                telegramService.sendMessageWithInlineButton(
                        recipient.getTelegramChatId(),
                        text,
                        "✅ Подтвердить поступление владельцу",
                        callbackData
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось отправить Telegram-запрос approvalId={}, userId={}",
                        request.approvalId(),
                        recipient.getId(),
                        exception
                );
            }
        }
        paymentIssueReminderService.notifyOrderIssue(
                request.orderId(),
                OWNER_APPROVAL_REMINDER_SOURCE,
                request.approvalId(),
                title,
                limit(text, 1000)
        );
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

    private String commonInvoiceNotificationText(CommonInvoiceReviewRequest request) {
        String title = valueOrDefault(request.invoiceTitle(), "общий счет");
        String orders = request.orderIds() == null || request.orderIds().isEmpty()
                ? "не указаны"
                : request.orderIds().stream().map(id -> "№" + id).reduce((left, right) -> left + ", " + right).orElse("");
        String routes = request.closedRouteIds() == null || request.closedRouteIds().isEmpty()
                ? "не было"
                : request.closedRouteIds().stream().map(id -> "№" + id).reduce((left, right) -> left + ", " + right).orElse("");
        return "Менеджер отметил общий счет оплаченным переводом на карту."
                + "\nОбщий счет: №" + request.invoiceId() + " · " + title
                + "\nСумма вручную: " + rubles(request.amountKopecks()) + " ₽"
                + "\nМенеджер: " + valueOrDefault(request.actor(), "не указан")
                + "\nПричина: " + valueOrDefault(request.reason(), "не указана")
                + "\nЗаказы: " + orders
                + "\nЗакрытые одиночные инструкции: " + routes
                + "\nПроверьте поступление в выписке.";
    }

    private String ownerApprovalNotificationText(OwnerApprovalRequest request) {
        String company = valueOrDefault(request.companyTitle(), "не указана");
        return "Менеджер сообщает: клиент оплатил по реквизитам владельца, но текущий платёжный маршрут "
                + "не подтверждает это поступление автоматически."
                + "\nЗаказ: №" + request.orderId()
                + "\nКомпания: " + company
                + "\nСумма: " + rubles(request.amountKopecks()) + " ₽"
                + "\nМенеджер: " + valueOrDefault(request.actor(), "не указан")
                + "\nПричина: " + valueOrDefault(request.reason(), "не указана")
                + "\nТекущий платёжный источник: №" + request.paymentLinkId()
                + ", текущий статус " + valueOrDefault(request.linkStatus(), "неизвестен")
                + "\n\nНажмите кнопку только после проверки поступления на счёт владельца. "
                + "Система повторно проверит источник, безопасно закроет его при необходимости "
                + "и затем однократно отметит заказ оплаченным.";
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

    public record CommonInvoiceReviewRequest(
            Long invoiceId,
            String invoiceTitle,
            long amountKopecks,
            String actor,
            String reason,
            List<Long> orderIds,
            List<Long> closedRouteIds
    ) {
    }

    public record OwnerApprovalRequest(
            Long approvalId,
            String callbackToken,
            Long paymentLinkId,
            Long orderId,
            String companyTitle,
            long amountKopecks,
            String actor,
            String reason,
            String linkStatus
    ) {
    }
}
