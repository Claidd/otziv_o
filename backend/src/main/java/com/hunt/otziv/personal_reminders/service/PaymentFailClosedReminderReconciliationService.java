package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.payments.service.PaymentIssueResolutionEvidenceReader;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Completes only stale fail-closed cards whose order has a verified paid fact. */
@Service
@RequiredArgsConstructor
public class PaymentFailClosedReminderReconciliationService {

    public static final String LIVE_ROUTING_SOURCE = "PAYMENT_LIVE_ROUTING_FAIL_CLOSED";
    public static final Set<String> SOURCES = Set.of(
            "PAYMENT_FAIL_CLOSED", LIVE_ROUTING_SOURCE
    );

    private final PersonalReminderRepository reminders;
    private final PaymentIssueResolutionEvidenceReader evidenceReader;

    @Transactional
    public Outcome reconcile(Long reminderId, boolean dryRun) {
        var candidate = reminders.findOpenReconciliationCandidate(reminderId).orElse(null);
        if (candidate == null || !SOURCES.contains(candidate.getSourceType())) {
            return new Outcome(false, false, "no_longer_open");
        }
        Long orderId = candidate.getSourceOrderId();
        if (orderId == null || orderId <= 0) {
            return new Outcome(false, false, "missing_source_order");
        }
        if (!evidenceReader.lockOrder(orderId)) {
            return new Outcome(false, false, "order_missing_or_ambiguous");
        }
        PersonalReminder reminder = reminders.findOpenByIdForUpdate(reminderId).orElse(null);
        if (reminder == null
                || !SOURCES.contains(reminder.getSourceType())
                || !Objects.equals(reminder.getSourceType(), candidate.getSourceType())
                || !Objects.equals(reminder.getSourceOrderId(), orderId)) {
            return new Outcome(false, false, "no_longer_open");
        }
        var evidence = evidenceReader.confirmedPaidOrder(orderId);
        if (!evidence.confirmed()) {
            return new Outcome(false, false, evidence.reason());
        }
        if (dryRun) {
            return new Outcome(true, false, "dry_run_confirmed_paid_order");
        }
        reminder.setCompletedAt(Instant.now());
        reminders.save(reminder);
        return new Outcome(true, true, "closed_confirmed_paid_order");
    }

    public record Outcome(boolean eligible, boolean closed, String reason) {
    }
}
