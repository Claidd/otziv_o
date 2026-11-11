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
    public static final String COMMON_INVOICE_OWNER_APPROVAL_REMINDER_SOURCE =
            "COMMON_INVOICE_OWNER_RECEIPT_APPROVAL";
    public static final String COMMON_INVOICE_OWNER_REJECTION_REMINDER_SOURCE =
            "COMMON_INVOICE_OWNER_RECEIPT_REJECTED";

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

    /** A manager reported that the owner received a common-invoice transfer: owners and administrators decide. */
    public void notifyCommonInvoiceOwnerApprovalAfterCommit(CommonInvoiceOwnerApprovalRequest request) {
        if (request == null || request.reportId() == null || request.invoiceId() == null) {
            return;
        }
        runAfterCommit(() -> notifyCommonInvoiceOwnerApprovalNow(request));
    }

    /** The decision is made: nobody has to be reminded about this report any more. */
    public void closeCommonInvoiceOwnerApprovalReminders(Long reportId) {
        if (reportId != null) {
            personalReminderService.deleteSystemRemindersBySource(
                    COMMON_INVOICE_OWNER_APPROVAL_REMINDER_SOURCE,
                    reportId
            );
        }
    }

    /** Tells the manager who sent the report that the owner did not confirm the money. */
    public void notifyCommonInvoiceOwnerRejectionAfterCommit(CommonInvoiceOwnerRejection request) {
        if (request == null || request.reportId() == null || request.invoiceId() == null) {
            return;
        }
        runAfterCommit(() -> notifyCommonInvoiceOwnerRejectionNow(request));
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

    private void notifyCommonInvoiceOwnerApprovalNow(CommonInvoiceOwnerApprovalRequest request) {
        String text = commonInvoiceOwnerApprovalText(request);
        String title = "Подтвердить поступление владельцу по общему счёту №" + request.invoiceId();
        for (User recipient : recipients().values()) {
            remindAndTell(recipient, title, text, COMMON_INVOICE_OWNER_APPROVAL_REMINDER_SOURCE,
                    request.reportId(), request.sourceOrderId());
        }
    }

    private void notifyCommonInvoiceOwnerRejectionNow(CommonInvoiceOwnerRejection request) {
        User reporter = userService.findByUserName(clean(request.reportedBy()))
                .filter(User::isActive).orElse(null);
        if (reporter == null || reporter.getId() == null) {
            return;
        }
        String title = "Владелец не подтвердил оплату общего счёта №" + request.invoiceId();
        String text = "Поступление владельцу по общему счёту не подтверждено."
                + "\nОбщий счёт: №" + request.invoiceId() + " · " + valueOrDefault(request.invoiceTitle(), "общий счёт")
                + "\nРешение принял: " + displayName(request.decidedBy())
                + "\nСчёт остался неоплаченным. Уточните у клиента, кому он перевёл деньги, и отметьте оплату заново.";
        remindAndTell(reporter, title, text, COMMON_INVOICE_OWNER_REJECTION_REMINDER_SOURCE,
                request.reportId(), request.sourceOrderId());
    }

    /** One durable reminder per person and source, plus a best-effort personal Telegram message. */
    private void remindAndTell(User recipient, String title, String text, String source, Long sourceId, Long sourceOrderId) {
        try {
            if (!personalReminderService.hasOpenSystemReminder(recipient, source, sourceId)) {
                personalReminderService.createSystemReminderDueNow(
                        recipient, title, limit(text, 1000), source, sourceId, sourceOrderId);
            }
        } catch (RuntimeException exception) {
            log.warn("Не удалось создать напоминание source={}, sourceId={}, userId={}",
                    source, sourceId, recipient.getId(), exception);
        }
        if (recipient.getTelegramChatId() == null) {
            return;
        }
        try {
            telegramService.sendMessage(recipient.getTelegramChatId(), text);
        } catch (RuntimeException exception) {
            log.warn("Не удалось отправить Telegram source={}, sourceId={}, userId={}",
                    source, sourceId, recipient.getId(), exception);
        }
    }

    private String commonInvoiceOwnerApprovalText(CommonInvoiceOwnerApprovalRequest request) {
        String amount = rubles(request.ownerAmountKopecks()) + " ₽";
        if (request.totalKopecks() > 0 && request.totalKopecks() != request.ownerAmountKopecks()) {
            amount += " из " + rubles(request.totalKopecks()) + " ₽ по счёту";
        }
        return "Менеджер сообщил, что деньги по общему счёту получил владелец."
                + "\nОбщий счёт: №" + request.invoiceId() + " · " + valueOrDefault(request.invoiceTitle(), "общий счёт")
                + "\nВладельцу: " + amount
                + "\nМенеджер: " + displayName(request.reportedBy())
                + "\nКомментарий: " + valueOrDefault(request.reason(), "не указан")
                + "\n\nСчёт закроется только после решения владельца или администратора. Проверьте выписку, "
                + "откройте этот счёт в разделе «Общие счета» и нажмите «Оплачен»: "
                + "«Деньги пришли — закрыть счёт» или «Денег нет — отклонить».";
    }

    private String displayName(String username) {
        String login = clean(username);
        if (login.isBlank()) {
            return "не указан";
        }
        try {
            return userService.findByUserName(login).map(User::getFio).map(this::clean)
                    .filter(fio -> !fio.isBlank()).orElse(login);
        } catch (RuntimeException exception) {
            return login;
        }
    }

    private void notifyOwnerApprovalNow(OwnerApprovalRequest request) {
        String text = ownerApprovalNotificationText(request);
        String title = "Подтвердить поступление владельцу по заказу №" + request.orderId();
        String callbackData = OwnerManualCardPaymentApprovalCallbackData.encode(
                request.approvalId(),
                request.callbackToken()
        );
        Long managerGroupChatId = request.managerGroupChatId();
        if (managerGroupChatId != null && managerGroupChatId < 0) {
            try {
                telegramService.sendMessageWithInlineButton(
                        managerGroupChatId,
                        text,
                        "✅ Подтвердить поступление владельцу",
                        callbackData
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось отправить Telegram-запрос approvalId={}, managerId={}, groupChatId={}",
                        request.approvalId(),
                        request.managerId(),
                        managerGroupChatId,
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
                + "и однократно отметит заказ оплаченным после завершения всех работ и восстановлений.";
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

    public record CommonInvoiceOwnerApprovalRequest(
            Long reportId,
            Long invoiceId,
            String invoiceTitle,
            long ownerAmountKopecks,
            long totalKopecks,
            String reportedBy,
            String reason,
            Long sourceOrderId
    ) {
    }

    public record CommonInvoiceOwnerRejection(
            Long reportId,
            Long invoiceId,
            String invoiceTitle,
            String reportedBy,
            String decidedBy,
            Long sourceOrderId
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
            String linkStatus,
            Long managerId,
            Long managerGroupChatId
    ) {
    }
}
