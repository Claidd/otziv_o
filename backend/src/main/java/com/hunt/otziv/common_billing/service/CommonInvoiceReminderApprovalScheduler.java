package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ReviewApprovalReminderStates;
import com.hunt.otziv.p_products.review.exception.PublicationApprovalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceReminderApprovalScheduler {

    private final ReviewApprovalReminderStates stateService;
    private final CommonInvoiceReminderApprovalWorkflow approvalWorkflow;
    private final CommonBillingPublicationApprovalFailureMarker failureMarker;

    @Scheduled(fixedDelayString = "${common-billing.review-auto-approval.fixed-delay:PT5M}",
            initialDelayString = "${common-billing.review-auto-approval.initial-delay:PT1M}")
    public void approveEligibleOrders() {
        long afterId = 0;
        int inspected = 0;
        int approved = 0;
        while (inspected < 200) {
            var candidates = stateService.candidatesAfter(afterId, Math.min(50, 200 - inspected));
            if (candidates.isEmpty()) break;
            for (var candidate : candidates) {
                afterId = candidate.stateId();
                inspected++;
                try {
                    if (approvalWorkflow.approve(candidate.stateId())) approved++;
                } catch (PublicationApprovalException exception) {
                    failureMarker.markAttention(candidate.invoiceId(), exception);
                    log.warn("Review reminder auto-approval needs attention: invoiceId={} stateId={}",
                            candidate.invoiceId(), candidate.stateId(), exception);
                } catch (RuntimeException exception) {
                    log.warn("Review reminder auto-approval failed: invoiceId={} stateId={}",
                            candidate.invoiceId(), candidate.stateId(), exception);
                }
            }
        }
        if (approved > 0) log.info("Approved {} common-invoice review orders after seven delivered reminders", approved);
    }
}
