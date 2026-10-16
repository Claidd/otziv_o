package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ReviewApprovalReminderStates;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.api.ReviewReminderOrderApprovals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Approves only the order whose current review cycle received seven delivered reminders. */
@Service
@RequiredArgsConstructor
public class CommonInvoiceReminderApprovalWorkflow {

    public static final int REQUIRED_REMINDERS = 7;

    private final ReviewApprovalReminderStates stateService;
    private final ReviewReminderOrderApprovals orderApprovals;
    private final CommonInvoiceOrderRepository invoiceOrderRepository;
    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    @Transactional
    public boolean approve(Long stateId) {
        ReviewApprovalReminderStates.Evidence state = stateService.lockEligible(stateId, REQUIRED_REMINDERS).orElse(null);
        if (state == null) {
            return false;
        }
        if (!orderApprovals.lockEligible(state.orderId(), state.targetKey())) {
            return false;
        }
        CommonInvoiceOrder item = invoiceOrderRepository.findMembershipByOrderIdForRead(state.orderId()).orElse(null);
        if (item == null || item.getInvoice() == null || item.isPaid() || item.isUnpaid()) {
            return false;
        }
        Long invoiceId = item.getInvoice().getId();
        CommonInvoice invoice = manualPaymentWorkflow.lockedInvoiceAfterOrderPrelude(invoiceId).orElse(null);
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.COLLECTING
                || invoice.getSentAt() != null || item.getInvoice().getId() == null
                || !invoiceId.equals(item.getInvoice().getId())) {
            return false;
        }

        orderApprovals.approve(state.orderId(),
                "invoiceId=" + invoiceId + ";source=seven_delivered_review_reminders;sentCount=" + state.sentCount());
        stateService.complete(stateId);
        manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
        return true;
    }

}
