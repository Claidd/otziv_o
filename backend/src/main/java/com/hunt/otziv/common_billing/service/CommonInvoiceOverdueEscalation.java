package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.settings.api.BackgroundAutomationSettings;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static com.hunt.otziv.config.settings.api.BackgroundAutomationSettings.*;

/**
 * Payment overdue for common invoices, matching the single-order escalation: an unpaid invoice is
 * announced to its manager {@link CommonInvoiceOverdueNotices#NOTICE_DAYS} days before the move,
 * and the autoresponder moves it to "Не оплачено" (with bad review tasks) once the overdue period
 * and the notice period have both passed. Every move re-reads the autoresponder switches fresh
 * and re-checks the invoice under its lock; bank paper invoices and part-paid invoices stay a
 * manager decision on the Control board.
 */
@Service
@Slf4j
public class CommonInvoiceOverdueEscalation implements CommonInvoiceOverdueNotices {

    static final String LIVE_ENABLED = "client.messages.payment-overdue.live-enabled";
    static final String OVERDUE_DAYS = "client.messages.payment-overdue-days";
    static final int DEFAULT_OVERDUE_DAYS = 30;
    static final Set<CommonInvoiceStatus> ESCALATION_STATUSES =
            Set.of(CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER);
    private static final String LEASE = "common-invoice-overdue-escalation";
    private static final int BATCH = 50;

    private final CommonInvoiceRepository invoices;
    private final CommonInvoiceArchiveWorkflow archiveWorkflow;
    private final BackgroundAutomationSettings automation;
    private final SchedulerLeaseService leases;
    private final TransactionTemplate perInvoice;

    public CommonInvoiceOverdueEscalation(
            CommonInvoiceRepository invoices,
            CommonInvoiceArchiveWorkflow archiveWorkflow,
            BackgroundAutomationSettings automation,
            SchedulerLeaseService leases,
            PlatformTransactionManager transactionManager
    ) {
        this.invoices = invoices;
        this.archiveWorkflow = archiveWorkflow;
        this.automation = automation;
        this.leases = leases;
        this.perInvoice = new TransactionTemplate(transactionManager);
        this.perInvoice.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.perInvoice.setTimeout(60);
    }

    /** Same switches as the order escalation: master switch, pause, reminders, overdue and live mode. */
    boolean escalationAllowed(LocalDateTime now) {
        return switchesOn() && pausedUntil(now) == null;
    }

    private boolean switchesOn() {
        try {
            return automation.getBooleanFreshFailClosed(CLIENT_MESSAGES_WORKER_ENABLED, true)
                    && automation.getBooleanFreshFailClosed(CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true)
                    && automation.getBooleanFreshFailClosed(CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, true)
                    && automation.getBooleanFreshFailClosed(LIVE_ENABLED, false);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** End of an active autoresponder pause, else null; an unreadable pause counts as indefinite. */
    private LocalDateTime pausedUntil(LocalDateTime now) {
        try {
            String pause = automation.getStringFresh(CLIENT_MESSAGES_PAUSED_UNTIL, null);
            if (pause == null || pause.isBlank()) return null;
            LocalDateTime until = LocalDateTime.parse(pause.trim());
            return until.isAfter(now) ? until : null;
        } catch (RuntimeException unavailable) {
            return LocalDateTime.MAX;
        }
    }

    int overdueDays() {
        try {
            String configured = automation.getStringFresh(OVERDUE_DAYS, String.valueOf(DEFAULT_OVERDUE_DAYS));
            int days = Integer.parseInt(configured.trim());
            return days <= 0 ? DEFAULT_OVERDUE_DAYS : Math.max(NOTICE_DAYS + 1, Math.min(365, days));
        } catch (RuntimeException invalid) {
            return DEFAULT_OVERDUE_DAYS;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Approaching> pendingNotices(LocalDateTime now) {
        if (now == null || !escalationAllowed(now)) {
            return List.of();
        }
        int days = overdueDays();
        return invoices.findOverdueNoticeCandidates(
                        ESCALATION_STATUSES,
                        now.minusDays(days - NOTICE_DAYS),
                        PageRequest.of(0, 500))
                .stream()
                .filter(row -> row != null && row.length >= 2 && row[0] instanceof CommonInvoice
                        && row[1] instanceof Number && eligible((CommonInvoice) row[0]))
                .map(row -> approaching((CommonInvoice) row[0], ((Number) row[1]).longValue(), now, days))
                .toList();
    }

    @Override
    @Transactional
    public boolean recordNotice(long invoiceId, LocalDateTime listedSentAt, LocalDateTime noticeAt) {
        CommonInvoice invoice = invoices.findByIdForUpdate(invoiceId).orElse(null);
        if (invoice == null || noticeAt == null || !eligible(invoice)
                || !Objects.equals(invoice.getSentAt(), listedSentAt)
                || noticedForCurrentCycle(invoice)) {
            return false;
        }
        invoice.setOverdueNoticeAt(noticeAt);
        invoices.save(invoice);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<LocalDateTime> automaticMoveNotBefore(long invoiceId, LocalDateTime now) {
        if (now == null || !switchesOn()) {
            return java.util.Optional.empty();
        }
        // A pause postpones notices and moves; it does not cancel them.
        LocalDateTime paused = pausedUntil(now);
        if (LocalDateTime.MAX.equals(paused)) {
            return java.util.Optional.empty();
        }
        LocalDateTime resume = paused == null ? now : paused;
        return invoices.findById(invoiceId).filter(this::eligible).map(invoice -> {
            LocalDateTime overdueAt = invoice.getSentAt().plusDays(overdueDays());
            LocalDateTime afterNotice = noticedForCurrentCycle(invoice)
                    ? invoice.getOverdueNoticeAt().plusDays(NOTICE_DAYS)
                    : resume.plusDays(NOTICE_DAYS);
            LocalDateTime earliest = overdueAt.isAfter(afterNotice) ? overdueAt : afterNotice;
            return earliest.isAfter(resume) ? earliest : resume;
        });
    }

    @Scheduled(
            cron = "${common-billing.overdue-escalation.cron:0 7/15 10-19 * * *}",
            zone = "${common-billing.overdue-escalation.zone:Asia/Irkutsk}"
    )
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void escalate() {
        LocalDateTime now = LocalDateTime.now();
        if (!escalationAllowed(now)) {
            return;
        }
        var acquired = leases.tryAcquire(LEASE, Duration.ofMinutes(5));
        if (acquired.isEmpty()) {
            return;
        }
        var lease = acquired.get();
        try {
            int days = overdueDays();
            List<Long> ids = invoices.findOverdueEscalationCandidateIds(
                    ESCALATION_STATUSES,
                    now.minusDays(days),
                    now.minusDays(NOTICE_DAYS),
                    PageRequest.of(0, BATCH));
            int moved = 0;
            for (Long id : ids) {
                try {
                    Boolean done = perInvoice.execute(status -> {
                        leases.holdForTransaction(lease, Duration.ofMinutes(5));
                        return escalateOne(id, LocalDateTime.now());
                    });
                    if (Boolean.TRUE.equals(done)) moved++;
                } catch (RuntimeException held) {
                    log.warn("Common invoice overdue move held: invoiceId={}, errorType={}, message={}",
                            id, held.getClass().getSimpleName(), held.getMessage());
                }
            }
            if (moved > 0) {
                log.info("Common invoices moved to Не оплачено after the overdue period: {}", moved);
            }
        } finally {
            leases.release(lease);
        }
    }

    /** Joins the caller's transaction; the switches are read again just before the move. */
    boolean escalateOne(long invoiceId, LocalDateTime now) {
        if (!escalationAllowed(now)) {
            return false;
        }
        int days = overdueDays();
        return archiveWorkflow.markUnpaidAfterOverdue(invoiceId, invoice -> stillDue(invoice, now, days));
    }

    boolean stillDue(CommonInvoice invoice, LocalDateTime now, int days) {
        return eligible(invoice)
                && escalationAllowed(now)
                && !invoice.getSentAt().isAfter(now.minusDays(days))
                && noticedForCurrentCycle(invoice)
                && !invoice.getOverdueNoticeAt().isAfter(now.minusDays(NOTICE_DAYS));
    }

    private boolean eligible(CommonInvoice invoice) {
        return invoice != null
                && ESCALATION_STATUSES.contains(invoice.getStatus())
                && !CommonInvoiceRouteState.isOwnerPaperInvoice(invoice)
                && !invoice.isPaperInvoiceAutomationHold()
                && !invoice.isPaperInvoiceReviewHold()
                && invoice.getSentAt() != null
                && invoice.getPaidKopecks() == 0
                && invoice.getClientReportedAt() == null
                && blank(invoice.getLastError())
                && blank(invoice.getPaymentSuccessNotificationError());
    }

    private static boolean noticedForCurrentCycle(CommonInvoice invoice) {
        return invoice.getOverdueNoticeAt() != null && invoice.getSentAt() != null
                && !invoice.getOverdueNoticeAt().isBefore(invoice.getSentAt());
    }

    private Approaching approaching(CommonInvoice invoice, long managerId, LocalDateTime now, int days) {
        LocalDateTime overdueAt = invoice.getSentAt().plusDays(days);
        LocalDateTime earliestAfterNotice = now.plusDays(NOTICE_DAYS);
        return new Approaching(
                invoice.getId(),
                managerId,
                invoice.getAccount() == null || invoice.getAccount().getName() == null
                        ? "Общий счёт #" + invoice.getId() : invoice.getAccount().getName(),
                Math.max(0, invoice.getAmountKopecks() - invoice.getPaidKopecks()),
                invoice.getSentAt(),
                Math.max(0, ChronoUnit.DAYS.between(invoice.getSentAt().toLocalDate(), now.toLocalDate())),
                overdueAt.isAfter(earliestAfterNotice) ? overdueAt : earliestAfterNotice
        );
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
