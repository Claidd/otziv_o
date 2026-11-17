package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.p_products.model.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommonInvoiceRepricingDiagnosticsTest {
    private static final String OPERATION = "bad-review-invoice:272:1";
    private static final LocalDateTime PREPARED = LocalDateTime.of(2026, 11, 14, 10, 0);
    private static final LocalDateTime ATTEMPTED = PREPARED.plusMinutes(2);

    @Mock private CommonInvoiceMessageQueue messages;
    @Mock private CommonInvoiceSettlementService settlement;
    @InjectMocks private CommonInvoiceDetailsAssembler assembler;
    private CommonInvoice invoice;
    private List<CommonInvoiceOrder> items;

    @BeforeEach
    void setUp() {
        invoice = new CommonInvoice();
        invoice.setId(272L);
        invoice.setBadReviewRevision(1L);
        invoice.setBadReviewPayable(true);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setPreviousStatus("UNPAID");
        invoice.setAmountKopecks(140000L);
        invoice.setPaymentMessageOperationId(OPERATION);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setLastError(CommonInvoiceMembershipWorkflow.BAD_REVIEW_SUPPLEMENT_REQUIRED + "task=1723");
        when(settlement.normalize(invoice.getLastError())).thenReturn(invoice.getLastError());
        var order = new Order();
        var item = new CommonInvoiceOrder();
        item.setOrder(order);
        item.setActiveMembership(true);
        items = List.of(item);
    }

    @ParameterizedTest
    @CsvSource({
        "SENT,,true",
        "UNKNOWN,whatsapp_history_checked_no_receipt,true",
        "UNKNOWN,provider_timeout,false",
        "UNKNOWN,provider_receipt_missing,false",
        "SENDING,,false",
        "FAILED,gateway_unavailable,false"
    })
    void exposesAmountDifferenceButRequiresReviewedDeliveryOutcome(String state, String error, boolean available) {
        prepareAmountAndRow();
        when(messages.snapshot(OPERATION)).thenReturn(previous());

        var review = assembler.repricingReview(invoice, items, new DeliveryOperation(OPERATION, state, 1, error));

        assertThat(review.previousKopecks()).isEqualTo(140000L);
        assertThat(review.currentKopecks()).isEqualTo(180000L);
        assertThat(review.preparedAt()).isEqualTo(PREPARED);
        assertThat(review.dispatchStartedAt()).isEqualTo(ATTEMPTED);
        assertThat(review.statementReviewAvailable()).isEqualTo(available);
    }

    @Test
    void unreadableHistoricalSnapshotKeepsDiagnosticsVisibleAndResolutionBlocked() {
        prepareAmountAndRow();
        when(messages.snapshot(OPERATION)).thenThrow(new IllegalStateException("Retired key"));
        var delivery = new DeliveryOperation(OPERATION, "UNKNOWN", 1, "whatsapp_history_checked_no_receipt");
        when(messages.status(OPERATION)).thenReturn(delivery);

        var review = assembler.repricingReview(invoice, items, delivery);

        assertThat(review.previousKopecks()).isEqualTo(140000L);
        assertThat(review.currentKopecks()).isEqualTo(180000L);
        assertThat(review.statementReviewAvailable()).isFalse();
        assertThat(assembler.needsRepricingReview(invoice, items)).isTrue();
    }

    @Test
    void receiptRecordedDuringReviewMakesRepricingUnavailable() {
        prepareAmountAndRow();
        when(messages.snapshot(OPERATION)).thenReturn(previous());
        invoice.setPaidKopecks(140000L);

        var review = assembler.repricingReview(invoice, items, new DeliveryOperation(OPERATION, "SENT", 1, null));

        assertThat(review.statementReviewAvailable()).isFalse();
    }

    @Test
    void supersededOperationCannotOfferAnotherReplacement() {
        when(messages.reviewRow(272L, OPERATION)).thenReturn(
                new CommonInvoiceMessageQueue.ReviewRow(PREPARED, ATTEMPTED, "bad-review-invoice:272:2"));

        var review = assembler.repricingReview(invoice, items, new DeliveryOperation(OPERATION, "SENT", 1, null));

        assertThat(review).isNull();
        verify(messages, never()).snapshot(anyString());
    }

    private void prepareAmountAndRow() {
        when(messages.reviewRow(272L, OPERATION)).thenReturn(new CommonInvoiceMessageQueue.ReviewRow(PREPARED, ATTEMPTED, null));
        when(settlement.payableSum(items.getFirst().getOrder())).thenReturn(new BigDecimal("1800"));
        when(settlement.amountKopecks(new BigDecimal("1800"))).thenReturn(180000L);
    }

    private PreparedCommonInvoiceMessage previous() {
        return new PreparedCommonInvoiceMessage(272L, null, "whatsapp_test", "test-group", "Previous invoice",
                "+79000000005", false, false, false, OPERATION, false, null, 140000L);
    }
}
