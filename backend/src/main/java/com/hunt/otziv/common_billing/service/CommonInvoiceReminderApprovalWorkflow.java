package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryHoldService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Approves only the order whose current review cycle received seven delivered reminders. */
@Service
@RequiredArgsConstructor
public class CommonInvoiceReminderApprovalWorkflow {

    public static final int REQUIRED_REMINDERS = 7;

    private final ScheduledClientMessageStateRepository stateRepository;
    private final OrderRepository orderRepository;
    private final CommonInvoiceOrderRepository invoiceOrderRepository;
    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;
    private final OrderPublicationApprovalService publicationApprovalService;
    private final ReviewRecoveryHoldService reviewRecoveryHoldService;

    @Transactional
    public boolean approve(Long stateId) {
        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElse(null);
        if (state == null || state.getScenario() != ClientMessageScenario.REVIEW_CHECK_REMINDER
                || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || state.getSentCount() < REQUIRED_REMINDERS || state.getOrderId() == null
                || state.getDeliveryStatus() != null) {
            return false;
        }
        Order order = orderRepository.findByIdForMutation(state.getOrderId()).orElse(null);
        if (order == null || order.getStatus() == null || !"На проверке".equals(order.getStatus().getTitle())
                || !currentCycle(state, order) || reviewRecoveryHoldService.shouldPauseClientMessages(order)) {
            return false;
        }
        CommonInvoiceOrder item = invoiceOrderRepository.findMembershipByOrderIdForRead(order.getId()).orElse(null);
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

        publicationApprovalService.approveExistingOrderAfterReminders(order.getId(),
                "invoiceId=" + invoiceId + ";source=seven_delivered_review_reminders;sentCount=" + state.getSentCount());
        state.setStatus(ScheduledMessageStateStatus.DONE);
        state.setNextAttemptAt(null);
        state.setLockedUntil(null);
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        stateRepository.save(state);
        manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
        return true;
    }

    private boolean currentCycle(ScheduledClientMessageState state, Order order) {
        LocalDateTime changedAt = order.getStatusChangedAt();
        if (changedAt == null && order.getChanged() != null) changedAt = order.getChanged().atStartOfDay();
        if (changedAt == null && order.getCreated() != null) changedAt = order.getCreated().atStartOfDay();
        return changedAt != null && ("order:" + order.getId() + ":" + changedAt.withNano(0)).equals(state.getTargetKey());
    }
}
