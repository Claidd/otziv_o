package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.util.LinkedHashMap;
import java.util.List;
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
public class PaymentIssueReminderService {

    public static final String SOURCE_PAYMENT_FAIL_CLOSED = "PAYMENT_FAIL_CLOSED";

    private final OrderRepository orderRepository;
    private final UserService userService;
    private final PersonalReminderService personalReminderService;

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
        Runnable action = () -> notifyOrderIssue(orderId, sourceType, sourceId, title, text);
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
        if (order == null || order.getId() == null || order.getId() <= 0) {
            return;
        }
        String cleanSourceType = sourceType(sourceType);
        Long cleanSourceId = sourceId == null || sourceId <= 0 ? order.getId() : sourceId;
        String cleanTitle = limit(valueOrDefault(title, "Нужна проверка оплаты заказа №" + order.getId()), 120);
        String cleanText = limit(valueOrDefault(text, defaultText(order)), 1000);

        for (User recipient : recipients(order).values()) {
            try {
                // Treat a repeated fail-closed signal as an update: keep exactly one
                // open card per recipient/source and refresh its text/updatedAt.
                personalReminderService.deleteSystemReminderBySource(recipient, cleanSourceType, cleanSourceId);
                personalReminderService.createSystemReminderDueNow(
                        recipient,
                        cleanTitle,
                        cleanText,
                        cleanSourceType,
                        cleanSourceId,
                        order.getId()
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "Не удалось создать платёжное замечание orderId={}, sourceType={}, sourceId={}, userId={}",
                        order.getId(),
                        cleanSourceType,
                        cleanSourceId,
                        recipient.getId(),
                        exception
                );
            }
        }
    }

    /** Clears the deduplicated staff task after an automatic retry succeeds. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveOrderIssue(Long orderId, String sourceType, Long sourceId) {
        if (orderId == null || orderId <= 0) {
            return;
        }
        orderRepository.findByIdForOrderDto(orderId).ifPresent(order -> {
            String cleanSourceType = sourceType(sourceType);
            Long cleanSourceId = sourceId == null || sourceId <= 0 ? orderId : sourceId;
            for (User recipient : recipients(order).values()) {
                try {
                    personalReminderService.deleteSystemReminderBySource(
                            recipient,
                            cleanSourceType,
                            cleanSourceId
                    );
                } catch (RuntimeException exception) {
                    log.warn(
                            "Не удалось закрыть платёжное замечание orderId={}, sourceType={}, sourceId={}, userId={}",
                            orderId,
                            cleanSourceType,
                            cleanSourceId,
                            recipient.getId(),
                            exception
                    );
                }
            }
        });
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
