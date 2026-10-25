package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ReviewReminderClosure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceReminderApprovalScheduler {

    // Kept bean/config name for deployment compatibility. The owner's policy now archives both order types.
    private final ReviewReminderClosure closure;

    @Scheduled(fixedDelayString = "${common-billing.review-auto-approval.fixed-delay:PT5M}",
            initialDelayString = "${common-billing.review-auto-approval.initial-delay:PT1M}")
    public void approveEligibleOrders() {
        long afterId = 0;
        int inspected = 0;
        int approved = 0;
        while (inspected < 200) {
            var candidates = closure.archiveCandidates(afterId, Math.min(50, 200 - inspected));
            if (candidates.isEmpty()) break;
            for (var candidate : candidates) {
                afterId = candidate;
                inspected++;
                try {
                    if (closure.archiveAfterReminders(candidate).archived()) approved++;
                } catch (RuntimeException exception) {
                    log.warn("Review reminder auto-archive needs attention: stateId={}", candidate, exception);
                }
            }
        }
        if (approved > 0) log.info("Archived {} review orders after seven delivered reminders; notices queued", approved);
    }
}
