package com.hunt.otziv.payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentAccountingMismatchView;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
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
    public static final String ORDER_FACT_ERROR_PREFIX = "payment_order_fact_mismatch:";
    public static final String ORDER_FACT_REMINDER_SOURCE = "PAYMENT_ORDER_FACT_MISMATCH";
    public static final String RESERVE_OVERRUN_REMINDER_SOURCE = "CONTRACTOR_RESERVE_OVERRUN";
    private static final Set<PaymentLinkStatus> MONEY_RECEIVED_STATUSES = Set.of(
            PaymentLinkStatus.CONFIRMED,
            PaymentLinkStatus.AMOUNT_MISMATCH
    );

    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentIssueReminderService paymentIssueReminderService;
    private final BusinessAuditService businessAuditService;
    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            reconcile();
        } catch (RuntimeException exception) {
            // Diagnostics must fail open for application availability. Every
            // financial writer still has its own transactional/DB guards.
            log.error("Startup financial integrity reconciliation failed", exception);
        }
    }

    @Scheduled(
            cron = "${otziv.payments.accounting-reconciliation.cron:0 15 4 * * *}",
            zone = "Asia/Irkutsk"
    )
    @Transactional
    public void reconcile() {
        int checkMismatches = reconcileSince(LocalDateTime.now().minusDays(180));
        int orderFactMismatches = reconcileOrderFacts();
        int reserveOverruns = reconcileReserveOverruns();
        if (checkMismatches + orderFactMismatches > 0) {
            log.error(
                    "Financial integrity reconciliation: checkMismatches={}, orderFactMismatches={}, reserveOverruns={}",
                    checkMismatches,
                    orderFactMismatches,
                    reserveOverruns
            );
        } else if (reserveOverruns > 0) {
            log.warn(
                    "Financial exposure reconciliation: reserveOverruns={} "
                            + "(previously issued routes remain reserved)",
                    reserveOverruns
            );
        }
    }

    int reconcileOrderFacts() {
        List<OrderFactMismatch> mismatches = jdbcTemplate.query(ORDER_FACT_MISMATCH_SQL, (rs, rowNum) ->
                new OrderFactMismatch(
                        rs.getLong("order_id"),
                        rs.getString("status_title"),
                        rs.getLong("active_check_count"),
                        rs.getLong("check_kopecks"),
                        rs.getLong("evidence_kopecks"),
                        rs.getLong("reconciliation_adjustment_kopecks"),
                        rs.getLong("reconciled_evidence_kopecks"),
                        rs.getLong("payable_kopecks"),
                        rs.getLong("cash_link_count"),
                        rs.getLong("reconciliation_count")
                ));
        if (mismatches == null) {
            return 0;
        }
        mismatches.forEach(this::flagOrderFact);
        return mismatches.size();
    }

    int reconcileReserveOverruns() {
        List<ReserveOverrun> overruns = jdbcTemplate.query(RESERVE_OVERRUN_SQL, (rs, rowNum) -> {
            long sampleOrderId = rs.getLong("sample_order_id");
            boolean sampleOrderMissing = rs.wasNull();
            return new ReserveOverrun(
                    rs.getLong("profile_id"),
                    sampleOrderMissing ? null : sampleOrderId,
                    rs.getLong("accrued_kopecks"),
                    rs.getLong("paid_kopecks"),
                    rs.getLong("outstanding_kopecks"),
                    rs.getLong("overrun_kopecks")
            );
        });
        if (overruns == null) {
            return 0;
        }
        overruns.stream()
                .filter(overrun -> overrun.sampleOrderId() != null)
                .forEach(this::flagReserveOverrun);
        return overruns.size();
    }

    private void flagOrderFact(OrderFactMismatch mismatch) {
        String error = ORDER_FACT_ERROR_PREFIX
                + " status=" + mismatch.statusTitle()
                + "; activeChecks=" + mismatch.activeCheckCount()
                + "; checks=" + mismatch.checkKopecks()
                + "; evidence=" + mismatch.evidenceKopecks()
                + "; adjustment=" + mismatch.reconciliationAdjustmentKopecks()
                + "; reconciledEvidence=" + mismatch.reconciledEvidenceKopecks()
                + "; payable=" + mismatch.payableKopecks()
                + "; cashLinks=" + mismatch.cashLinkCount()
                + "; reconciliations=" + mismatch.reconciliationCount()
                + "; автоматическое изменение денег запрещено";
        paymentLinkRepository.findByOrder_IdAndStatusIn(mismatch.orderId(), MONEY_RECEIVED_STATUSES)
                .stream()
                .max(Comparator.comparing(
                        PaymentLink::getPaidAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ).thenComparing(PaymentLink::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(link -> link.getLastError() == null
                        || link.getLastError().isBlank()
                        || link.getLastError().startsWith(ORDER_FACT_ERROR_PREFIX))
                .ifPresent(link -> {
                    link.setLastError(error);
                    paymentLinkRepository.save(link);
                });
        paymentIssueReminderService.notifyOrderIssue(
                mismatch.orderId(),
                ORDER_FACT_REMINDER_SOURCE,
                mismatch.orderId(),
                "Нужна сверка факта оплаты заказа №" + mismatch.orderId(),
                "Статус: " + mismatch.statusTitle()
                        + "; активных чеков: " + mismatch.activeCheckCount()
                        + "; по чекам: " + rubles(mismatch.checkKopecks()) + " ₽"
                        + "; подтверждено: " + rubles(mismatch.evidenceKopecks()) + " ₽"
                        + "; принятая корректировка: "
                        + rubles(mismatch.reconciliationAdjustmentKopecks()) + " ₽"
                        + "; после корректировки: "
                        + rubles(mismatch.reconciledEvidenceKopecks()) + " ₽"
                        + "; текущая стоимость: " + rubles(mismatch.payableKopecks()) + " ₽. "
                        + "Проверьте банк и возврат; система деньги не меняла."
        );
        businessAuditService.recordSafely(
                "PAYMENT_ORDER_FACT_MISMATCH_DETECTED",
                "ORDER",
                mismatch.orderId(),
                mismatch.orderId(),
                null,
                mismatch.checkKopecks(),
                mismatch.reconciledEvidenceKopecks(),
                error
        );
    }

    private void flagReserveOverrun(ReserveOverrun overrun) {
        paymentIssueReminderService.notifyOrderIssue(
                overrun.sampleOrderId(),
                RESERVE_OVERRUN_REMINDER_SOURCE,
                overrun.profileId(),
                "Резерв реквизитов выше заработка",
                "Профиль №" + overrun.profileId()
                        + ": начислено " + rubles(overrun.accruedKopecks()) + " ₽"
                        + ", уже подтверждено " + rubles(overrun.paidKopecks()) + " ₽"
                        + ", ожидает по ранее выданным реквизитам " + rubles(overrun.outstandingKopecks()) + " ₽"
                        + ", превышение " + rubles(overrun.overrunKopecks()) + " ₽. "
                        + "Старые реквизиты заморожены, новые сверх лимита не выдаются; нужна ручная сверка поступлений."
        );
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

        paymentIssueReminderService.notifyOrderIssue(
                orderId,
                REMINDER_SOURCE,
                orderId,
                "Нужна сверка оплаты заказа №" + orderId,
                "Сумма подтвержденных платежей: " + rubles(confirmed)
                        + " ₽, сумма активных чеков: " + rubles(checked)
                        + " ₽. Проверьте банк и первичный документ; система не меняла деньги автоматически."
        );
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

    private long kopecks(BigDecimal value) {
        return value == null ? 0L : value.longValue();
    }

    private String rubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2).stripTrailingZeros().toPlainString();
    }

    record OrderFactMismatch(
            long orderId,
            String statusTitle,
            long activeCheckCount,
            long checkKopecks,
            long evidenceKopecks,
            long reconciliationAdjustmentKopecks,
            long reconciledEvidenceKopecks,
            long payableKopecks,
            long cashLinkCount,
            long reconciliationCount
    ) {
    }

    record ReserveOverrun(
            long profileId,
            Long sampleOrderId,
            long accruedKopecks,
            long paidKopecks,
            long outstandingKopecks,
            long overrunKopecks
    ) {
    }

    private static final String ORDER_FACT_MISMATCH_SQL = """
            SELECT facts.*
            FROM (
                SELECT base_order.order_id,
                       order_status.order_status_title AS status_title,
                       COALESCE(active_checks.check_count, 0) AS active_check_count,
                       COALESCE(active_checks.check_kopecks, 0) AS check_kopecks,
                       CASE
                           WHEN COALESCE(link_cash.cash_kopecks, 0) > 0 THEN link_cash.cash_kopecks
                           ELSE COALESCE(invoice_cash.cash_kopecks, 0)
                       END AS evidence_kopecks,
                       COALESCE(reconciliation.adjustment_kopecks, 0)
                           AS reconciliation_adjustment_kopecks,
                       CASE
                           WHEN COALESCE(link_cash.cash_kopecks, 0) > 0 THEN link_cash.cash_kopecks
                           ELSE COALESCE(invoice_cash.cash_kopecks, 0)
                       END + COALESCE(reconciliation.adjustment_kopecks, 0)
                           AS reconciled_evidence_kopecks,
                       ROUND((COALESCE(base_order.order_sum, 0.00)
                           + COALESCE(bad_tasks.done_sum, 0.00)) * 100) AS payable_kopecks,
                       COALESCE(link_cash.cash_link_count, 0) AS cash_link_count,
                       COALESCE(reconciliation.reconciliation_count, 0) AS reconciliation_count
                FROM orders base_order
                JOIN order_statuses order_status ON order_status.order_status_id = base_order.order_status
                LEFT JOIN (
                    SELECT check_order AS order_id,
                           COUNT(*) AS check_count,
                           ROUND(SUM(COALESCE(check_sum, 0.00)) * 100) AS check_kopecks
                    FROM payment_check
                    WHERE check_active = 1
                    GROUP BY check_order
                ) active_checks ON active_checks.order_id = base_order.order_id
                LEFT JOIN (
                    SELECT order_id,
                           COUNT(*) AS cash_link_count,
                           SUM(COALESCE(confirmed_amount_kopecks, reserved_amount_kopecks, amount_kopecks)) AS cash_kopecks
                    FROM payment_links
                    WHERE status IN ('CONFIRMED', 'AMOUNT_MISMATCH')
                    GROUP BY order_id
                ) link_cash ON link_cash.order_id = base_order.order_id
                LEFT JOIN (
                    SELECT order_id, SUM(amount_kopecks) AS cash_kopecks
                    FROM common_invoice_orders
                    WHERE paid = 1
                    GROUP BY order_id
                ) invoice_cash ON invoice_cash.order_id = base_order.order_id
                LEFT JOIN (
                    SELECT order_id,
                           SUM(adjustment_kopecks) AS adjustment_kopecks,
                           COUNT(*) AS reconciliation_count
                    FROM order_payment_reconciliations
                    WHERE active = 1
                    GROUP BY order_id
                ) reconciliation ON reconciliation.order_id = base_order.order_id
                LEFT JOIN (
                    SELECT bad_review_task_order AS order_id,
                           SUM(CASE
                               WHEN bad_review_task_status = 'DONE' THEN COALESCE(bad_review_task_price, 0.00)
                               ELSE 0.00
                           END) AS done_sum
                    FROM bad_review_tasks
                    GROUP BY bad_review_task_order
                ) bad_tasks ON bad_tasks.order_id = base_order.order_id
            ) facts
            WHERE (facts.status_title = 'Оплачено' AND facts.active_check_count <> 1)
               OR (facts.status_title <> 'Оплачено' AND facts.active_check_count > 0)
               OR (facts.evidence_kopecks > 0
                   AND facts.reconciled_evidence_kopecks <> facts.payable_kopecks)
               OR (facts.cash_link_count > 1 AND facts.reconciliation_count = 0)
            ORDER BY facts.order_id
            """;

    private static final String RESERVE_OVERRUN_SQL = """
            SELECT capacity.*
            FROM (
                SELECT profile.id AS profile_id,
                       profile.opening_balance_kopecks + COALESCE(ledger.accrued_kopecks, 0) AS accrued_kopecks,
                       GREATEST(0, COALESCE(exposure.confirmed_kopecks, 0)
                           - COALESCE(exposure.returned_kopecks, 0)) AS paid_kopecks,
                       COALESCE(exposure.outstanding_kopecks, 0) AS outstanding_kopecks,
                       GREATEST(
                           0,
                           GREATEST(0, COALESCE(exposure.confirmed_kopecks, 0)
                               - COALESCE(exposure.returned_kopecks, 0))
                               + COALESCE(exposure.outstanding_kopecks, 0)
                               - (profile.opening_balance_kopecks + COALESCE(ledger.accrued_kopecks, 0))
                       ) AS overrun_kopecks,
                       exposure.sample_order_id
                FROM contractor_payment_profiles profile
                LEFT JOIN (
                    SELECT profile_id,
                           SUM(CASE WHEN active = 1 THEN amount_kopecks ELSE 0 END) AS accrued_kopecks
                    FROM contractor_reward_ledger
                    GROUP BY profile_id
                ) ledger ON ledger.profile_id = profile.id
                LEFT JOIN (
                    SELECT recipient_profile_id,
                           SUM(GREATEST(0, confirmed_kopecks)) AS confirmed_kopecks,
                           SUM(GREATEST(0, returned_kopecks)) AS returned_kopecks,
                           SUM(CASE
                               WHEN status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                                   THEN GREATEST(0, amount_kopecks
                                       - GREATEST(0, confirmed_kopecks - returned_kopecks))
                               ELSE 0
                           END) AS outstanding_kopecks,
                           MIN(CASE
                               WHEN status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                                   THEN order_id
                               ELSE NULL
                           END) AS sample_order_id
                    FROM contractor_payment_allocations
                    WHERE mode = 'LIVE'
                    GROUP BY recipient_profile_id
                ) exposure ON exposure.recipient_profile_id = profile.id
            ) capacity
            WHERE capacity.overrun_kopecks > 0
            ORDER BY capacity.profile_id
            """;
}
