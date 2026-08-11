package com.hunt.otziv.payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentAccountingMismatchView;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detects accounting divergence without changing money, checks or salaries.
 * A human must select the source of truth before a financial repair.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentAccountingReconciliationService {

    public static final String ERROR_PREFIX = "payment_accounting_mismatch:";
    public static final String REMINDER_SOURCE = "PAYMENT_ACCOUNTING_MISMATCH";
    private static final Set<PaymentLinkStatus> MONEY_RECEIVED_STATUSES = Set.of(
            PaymentLinkStatus.CONFIRMED,
            PaymentLinkStatus.AMOUNT_MISMATCH
    );

    private final PaymentLinkRepository paymentLinkRepository;
    private final PersonalReminderService personalReminderService;
    private final UserService userService;
    private final BusinessAuditService businessAuditService;

    @Scheduled(
            cron = "${otziv.payments.accounting-reconciliation.cron:0 15 4 * * *}",
            zone = "Asia/Irkutsk"
    )
    @Transactional
    public void reconcile() {
        reconcileSince(LocalDateTime.now().minusDays(180));
    }

    int reconcileSince(LocalDateTime paidSince) {
        List<PaymentAccountingMismatchView> mismatches =
                paymentLinkRepository.findAccountingMismatches(paidSince);
        for (PaymentAccountingMismatchView mismatch : mismatches) {
            flag(mismatch);
        }
        if (!mismatches.isEmpty()) {
            log.error("Payment accounting reconciliation found {} mismatch(es)", mismatches.size());
        }
        return mismatches.size();
    }

    private void flag(PaymentAccountingMismatchView mismatch) {
        Long orderId = mismatch.getOrderId();
        long confirmed = kopecks(mismatch.getConfirmedKopecks());
        long checked = kopecks(mismatch.getCheckKopecks());
        String error = ERROR_PREFIX + " подтверждено " + confirmed
                + " коп., в активных чеках " + checked
                + " коп.; автоматическое исправление запрещено";

        paymentLinkRepository.findByOrder_IdAndStatusIn(orderId, MONEY_RECEIVED_STATUSES)
                .stream()
                .max(Comparator.comparing(
                        PaymentLink::getPaidAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ).thenComparing(PaymentLink::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(link -> link.getLastError() == null
                        || link.getLastError().isBlank()
                        || link.getLastError().startsWith(ERROR_PREFIX))
                .ifPresent(link -> {
                    link.setLastError(error);
                    paymentLinkRepository.save(link);
                });

        for (User recipient : recipients()) {
            if (!personalReminderService.hasOpenSystemReminder(recipient, REMINDER_SOURCE, orderId)) {
                personalReminderService.createSystemReminderDueNow(
                        recipient,
                        "Нужна сверка оплаты заказа №" + orderId,
                        "Сумма подтвержденных платежей: " + rubles(confirmed)
                                + " ₽, сумма активных чеков: " + rubles(checked)
                                + " ₽. Проверьте банк и первичный документ; система не меняла деньги автоматически.",
                        REMINDER_SOURCE,
                        orderId,
                        orderId
                );
            }
        }
        businessAuditService.recordSafely(
                "PAYMENT_ACCOUNTING_MISMATCH_DETECTED",
                "PAYMENT_LINK",
                orderId,
                orderId,
                null,
                checked,
                confirmed,
                error
        );
    }

    private List<User> recipients() {
        return java.util.stream.Stream.concat(
                        userService.getAllOwners("ROLE_OWNER").stream(),
                        userService.getAllOwners("ROLE_ADMIN").stream()
                )
                .filter(user -> user != null && user.getId() != null && user.isActive())
                .collect(java.util.stream.Collectors.toMap(
                        User::getId,
                        user -> user,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    private long kopecks(BigDecimal value) {
        return value == null ? 0L : value.longValue();
    }

    private String rubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2).stripTrailingZeros().toPlainString();
    }
}
