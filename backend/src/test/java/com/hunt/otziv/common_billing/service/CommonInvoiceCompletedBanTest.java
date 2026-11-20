package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommonInvoiceCompletedBanTest {
    private final CommonInvoiceRepository invoices = mock(CommonInvoiceRepository.class);
    private final CommonInvoiceOrderRepository items = mock(CommonInvoiceOrderRepository.class);
    private final BadReviewTaskService tasks = mock(BadReviewTaskService.class);
    private final CommonInvoiceManualPaymentWorkflow payments = mock(CommonInvoiceManualPaymentWorkflow.class);
    private final ReviewRecoveryGateService recovery = mock(ReviewRecoveryGateService.class);
    private final CommonInvoiceArchiveWorkflow workflow = new CommonInvoiceArchiveWorkflow(
            null, payments, null, mock(CommonInvoiceSettlementService.class, CALLS_REAL_METHODS), invoices, items,
            null, tasks, null, null, null, recovery);

    private CommonInvoice fixture() {
        var invoice = new CommonInvoice(); invoice.setId(373L); invoice.setStatus(CommonInvoiceStatus.BAN);
        invoice.setPreviousStatus("UNPAID"); invoice.setCloseReason("BAN"); invoice.setClosedBy("vika");
        invoice.setClosedAt(LocalDateTime.now().minusDays(1)); invoice.setAmountKopecks(240000);
        var status = new OrderStatus(); status.setTitle("Бан");
        var order = new Order(); order.setId(26194L); order.setStatus(status);
        var item = new CommonInvoiceOrder(); item.setOrder(order); item.setActiveMembership(true);
        when(invoices.findById(373L)).thenReturn(Optional.of(invoice));
        when(items.findByInvoiceIdWithOrders(373L)).thenReturn(List.of(item));
        when(tasks.getSummaryByOrderIds(List.of(26194L))).thenReturn(Map.of(26194L,
                new BadReviewTaskSummary(3, 0, 3, 0, null, null)));
        return invoice;
    }

    @Test void completedDecisionResolvesControlWithoutChangingBanDebtOrTasks() {
        var invoice = fixture();
        assertThat(workflow.completedBan(373L)).isTrue();
        assertThat(workflow.completedBan(373L)).isTrue();
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.BAN);
        assertThat(invoice.getPaidKopecks()).isZero(); assertThat(invoice.getAmountKopecks()).isEqualTo(240000);
        verify(invoices, never()).save(any()); verify(items, never()).save(any());
        verify(tasks, never()).cancelPendingTasksForOrder(any());
    }

    @ParameterizedTest @ValueSource(strings = {"actor", "reason", "previous", "error", "notification", "payment", "document", "unknownSend", "bank"})
    void incompleteOrFinanciallyUncertainBanRemainsVisible(String obstacle) {
        var invoice = fixture();
        switch (obstacle) {
            case "actor" -> invoice.setClosedBy(null);
            case "reason" -> invoice.setCloseReason(null);
            case "previous" -> invoice.setPreviousStatus("INVOICED");
            case "error" -> invoice.setLastError("needs reconciliation");
            case "notification" -> invoice.setPaymentSuccessNotificationError("unknown");
            case "payment" -> invoice.setPaidKopecks(1);
            case "document" -> invoice.setPaperBankDocumentId("document");
            case "unknownSend" -> invoice.setPaymentMessageOperationId("unknown-send");
            case "bank" -> when(payments.hasUnresolvedBanPayment(invoice)).thenReturn(true);
        }
        assertThat(workflow.completedBan(373L)).isFalse();
    }

    @Test void activeWorkMissingMembershipOrUnknownTaskSummaryMustNotCloseControl() {
        fixture();
        when(recovery.hasActiveRecoveryTasks(26194L)).thenReturn(true);
        assertThat(workflow.completedBan(373L)).isFalse();
        when(recovery.hasActiveRecoveryTasks(26194L)).thenReturn(false);
        when(tasks.getSummaryByOrderIds(any())).thenReturn(Map.of(26194L, new BadReviewTaskSummary(3,1,2,0,null,null)));
        assertThat(workflow.completedBan(373L)).isFalse();
        when(tasks.getSummaryByOrderIds(any())).thenReturn(Map.of());
        assertThat(workflow.completedBan(373L)).isFalse();
        when(items.findByInvoiceIdWithOrders(373L)).thenReturn(List.of());
        assertThat(workflow.completedBan(373L)).isFalse();
    }

    @Test void bankingGuardKeepsUnknownPaidAndCancellationPendingReferencesVisible() {
        var refs=mock(CommonInvoicePaymentRefRepository.class);
        var flow=mock(CommonInvoiceManualPaymentWorkflow.class,CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(flow,"paymentRefRepository",refs);
        org.springframework.test.util.ReflectionTestUtils.setField(flow,"settlementService",mock(CommonInvoiceSettlementService.class,CALLS_REAL_METHODS));
        var invoice=new CommonInvoice();invoice.setId(373L);
        var ref=new CommonInvoicePaymentRef();
        when(refs.findByInvoiceIdOrderByCreatedAtAsc(373L)).thenReturn(List.of(ref));
        for(String status:List.of("UNKNOWN","CURRENT","CONFIRMED","APPLIED","CANCEL_PENDING","CANCEL_FAILED","ARCHIVED")) {
            ref.setStatus(status);assertThat(flow.hasUnresolvedBanPayment(invoice)).as(status).isTrue();
        }
        ref.setStatus("CANCELED");assertThat(flow.hasUnresolvedBanPayment(invoice)).isFalse();
        invoice.setTbankPaymentId("still-current");assertThat(flow.hasUnresolvedBanPayment(invoice)).isTrue();
        verify(refs,never()).save(any());
    }
}
