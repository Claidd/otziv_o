package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.payments.api.PaymentIssueCompletionOperations;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates one visible manager-facing task for payment states that deliberately
 * fail closed. The financial services stay conservative; this bridge makes the
 * human follow-up visible to the responsible manager and duplicated to
 * OWNER/ADMIN for control.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentIssueReminderService implements PaymentIssueCompletionOperations {

    public static final String SOURCE_PAYMENT_FAIL_CLOSED = "PAYMENT_FAIL_CLOSED";
    public static final String SOURCE_PAYMENT_RETURN_RECONCILIATION = "PAYMENT_RETURN_RECONCILIATION";

    private final OrderRepository orderRepository;
    private final UserService userService;
    private final PersonalReminderService personalReminderService;
    private final PaymentIssueReminderTransactionExecutor transactionExecutor;

    public void notifyOrderIssueAfterCommit(
            Long orderId,
            String sourceType,
            Long sourceId,
            String title,
            String text
    ) {
        if (orderId == null || orderId <= 0) {
            return;
        }
        Runnable action = () -> {
            try {
                transactionExecutor.executeRequiresNew(
                        () -> notifyOrderIssue(orderId, sourceType, sourceId, title, text)
                );
            } catch (RuntimeException exception) {
                log.error(
                        "Не удалось сохранить платёжное замечание после commit: orderId={}, sourceType={}, sourceId={}",
                        orderId,
                        sourceType,
                        sourceId,
                        exception
                );
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        action.run();
                    }
                }
            });
            return;
        }
        action.run();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyOrderIssue(
            Long orderId,
            String sourceType,
            Long sourceId,
            String title,
            String text
    ) {
        if (orderId == null || orderId <= 0) {
            return;
        }
        orderRepository.findByIdForOrderDto(orderId).ifPresent(order ->
                notifyOrderIssue(order, sourceType, sourceId, title, text));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyOrderIssue(
            Order order,
            String sourceType,
            Long sourceId,
            String title,
            String text
    ) {
        persistOrderIssue(order, sourceType, sourceId, title, text);
    }

    /**
     * Durable path for a financial fail-closed transition. The caller's
     * marker and every visible reminder commit atomically; a reminder failure
     * therefore keeps the recovery outbox retryable.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureOrderIssuePersisted(
            Order order,
            String sourceType,
            Long sourceId,
            String title,
            String text
    ) {
        persistOrderIssue(order, sourceType, sourceId, title, text);
    }

    private void persistOrderIssue(
            Order order,
            String sourceType,
            Long sourceId,
            String title,
            String text
    ) {
        if (order == null || order.getId() == null || order.getId() <= 0) {
            throw new IllegalArgumentException("Заказ платёжного замечания обязателен");
        }
        String cleanSourceType = sourceType(sourceType);
        Long cleanSourceId = sourceId == null || sourceId <= 0 ? order.getId() : sourceId;
        String cleanTitle = limit(valueOrDefault(title, "Нужна проверка оплаты заказа №" + order.getId()), 120);
        String cleanText = reminderText(cleanSourceType, order.getId(), text, order);

        Map<Long, User> recipients = recipients(order);
        if (recipients.isEmpty()) {
            throw new IllegalStateException(
                    "Для платёжной сверки заказа " + order.getId() + " не найден активный получатель напоминания"
            );
        }
        for (User recipient : recipients.values()) {
            personalReminderService.upsertSystemReminderDueNow(
                    recipient,
                    cleanTitle,
                    cleanText,
                    cleanSourceType,
                    cleanSourceId,
                    order.getId()
            );
        }
    }

    /** Clears the deduplicated staff task after an automatic retry succeeds. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveOrderIssue(Long orderId, String sourceType, Long sourceId) {
        if (orderId == null || orderId <= 0) {
            return;
        }
        personalReminderService.deleteSystemRemindersBySource(
                sourceType(sourceType),
                sourceId == null || sourceId <= 0 ? orderId : sourceId
        );
    }

    /** Completes a resolved diagnostic warning while preserving its historical card. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int completeOrderIssue(Long orderId, String sourceType, Long sourceId) {
        if (orderId == null || orderId <= 0 || sourceId == null || sourceId <= 0) {
            throw new IllegalArgumentException("Источник и заказ завершаемого замечания обязательны");
        }
        return personalReminderService.completeOpenSystemRemindersBySourceAndOrder(
                sourceType(sourceType), sourceId, orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolveOrderIssueInCurrentTransaction(String sourceType, Long sourceId) {
        if (sourceId == null || sourceId <= 0) {
            throw new IllegalArgumentException("Источник закрываемого платёжного замечания обязателен");
        }
        personalReminderService.deleteSystemRemindersBySource(sourceType(sourceType), sourceId);
    }

    /** Completes only this common invoice's paid order warnings, retaining the original cards. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int completePaidCommonInvoiceOrderIssueInCurrentTransaction(Long invoiceId, Long orderId) {
        if (invoiceId == null || invoiceId <= 0 || orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("Общий счёт и оплаченный заказ обязательны");
        }
        return personalReminderService.completeOpenSystemRemindersBySourceAndOrder(
                SOURCE_PAYMENT_FAIL_CLOSED, invoiceId, orderId
        );
    }

    @Transactional(readOnly = true)
    public boolean hasOpenOrderIssue(String sourceType, Long sourceId) {
        return sourceId != null && sourceId > 0
                && personalReminderService.hasOpenSystemReminderBySource(
                        sourceType(sourceType), sourceId);
    }

    private Map<Long, User> recipients(Order order) {
        Map<Long, User> recipients = new LinkedHashMap<>();
        addRecipient(recipients, responsibleManager(order));
        addRecipients(recipients, userService.getAllOwners("ROLE_OWNER"));
        addRecipients(recipients, userService.getAllOwners("ROLE_ADMIN"));
        return recipients;
    }

    private User responsibleManager(Order order) {
        Manager manager = order == null ? null : order.getManager();
        if (manager != null && manager.getUser() != null) {
            return manager.getUser();
        }
        Company company = order == null ? null : order.getCompany();
        Manager companyManager = company == null ? null : company.getManager();
        return companyManager == null ? null : companyManager.getUser();
    }

    private void addRecipients(Map<Long, User> recipients, List<User> users) {
        if (users == null) {
            return;
        }
        users.forEach(user -> addRecipient(recipients, user));
    }

    private void addRecipient(Map<Long, User> recipients, User user) {
        if (user != null && user.getId() != null && user.isActive()) {
            recipients.putIfAbsent(user.getId(), user);
        }
    }

    private String defaultText(Order order) {
        return "По заказу №" + order.getId()
                + " возникла платёжная ситуация, которую система оставила на ручную проверку.";
    }

    private String reminderText(String sourceType, Long orderId, String text, Order order) {
        String cleanText = valueOrDefault(text, defaultText(order));
        if (!SOURCE_PAYMENT_FAIL_CLOSED.equals(sourceType)
                && !"PAYMENT_LIVE_ROUTING_FAIL_CLOSED".equals(sourceType)) {
            return limit(cleanText, 1000);
        }
        String normalized = cleanText.toLowerCase(Locale.ROOT);
        if (normalized.contains("deadlock")
                || normalized.contains("jdbc")
                || normalized.contains("sql [")
                || normalized.contains("sqlexception")
                || normalized.contains("sqlstate")
                || normalized.contains("lock wait timeout")
                || normalized.contains("for update")
                || normalized.contains("org.hibernate")
                || normalized.contains("com.mysql")) {
            // Keep the full exception in the automation state and server log, not in a staff task.
            return "Не удалось подготовить или сверить оплату из-за временной блокировки базы данных."
                    + "\nЗаказ: №" + orderId
                    + "\nПроверьте текущий статус заказа и платёжную ссылку. Если заказ не оплачен и счёт не выставлен, "
                    + "повторите попытку или выполните ручную проверку. Не создавайте второй счёт до проверки.";
        }
        return limit(cleanText, 1000);
    }

    private String sourceType(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.isBlank() ? SOURCE_PAYMENT_FAIL_CLOSED : limit(clean, 60);
    }

    private String valueOrDefault(String value, String fallback) {
        String clean = value == null ? "" : value.trim();
        return clean.isBlank() ? fallback : clean;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }
}
