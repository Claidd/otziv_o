package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonInvoiceRepricingRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceRepricingReview;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommonInvoiceReviewedAmountTest {
    private static final long INVOICE_ID = 272L;
    private static final String OLD_OPERATION = "bad-review-invoice:272:1";
    private static final String NEW_OPERATION = "bad-review-invoice:272:2";
    private static final String RECIPIENT_NUMBER = "+79000000005";
    private static final Principal MANAGER = () -> "test-manager";

    @Mock private CommonInvoiceDeliveryService delivery;
    @Mock private CommonInvoiceManualPaymentWorkflow visibility;
    @Mock private CommonInvoiceOrderRepository memberships;
    @Mock private CommonInvoiceDetailsAssembler details;
    @Mock private CommonInvoiceMessageQueue messages;
    @Mock private CommonInvoiceInitializationService initialization;
    @Mock private CommonInvoiceMembershipWorkflow amounts;
    @InjectMocks private CommonBillingService service;

    private CommonInvoice invoice;
    private List<CommonInvoiceOrder> items;
    private CommonInvoiceInitializationService.LockedInvoicePaymentPrelude prelude;

    @BeforeEach
    void setUp() {
        invoice = new CommonInvoice();
        invoice.setId(INVOICE_ID);
        invoice.setAmountKopecks(140000L);
        invoice.setBadReviewRevision(1);
        invoice.setPaymentMessageOperationId(OLD_OPERATION);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteManualRecipient("Test recipient");
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        items = List.of(new CommonInvoiceOrder());
        prelude = new CommonInvoiceInitializationService.LockedInvoicePaymentPrelude(invoice, Map.of());
        when(delivery.lockedInvoiceAfterStandalonePaymentPrelude(INVOICE_ID)).thenReturn(prelude);
        when(memberships.findByInvoiceIdWithOrders(INVOICE_ID)).thenReturn(items);
    }

    @Test
    void repeatedConfirmationReusesReplacementAndResizesTheSameRecipientOnce() {
        prepareReview();
        var previous = prepareSnapshot();
        when(messages.recordStatementReview(INVOICE_ID, OLD_OPERATION, NEW_OPERATION, MANAGER.getName())).thenReturn(true);
        when(initialization.reviewedRecipientProfile(invoice)).thenReturn(5L);
        when(delivery.telegramCopyTransferNumber(invoice)).thenReturn(RECIPIENT_NUMBER);
        when(messages.reviewRow(INVOICE_ID, OLD_OPERATION)).thenReturn(null,
                new CommonInvoiceMessageQueue.ReviewRow(null, null, NEW_OPERATION));

        service.replaceReviewedAmount(INVOICE_ID, request(true), MANAGER);
        service.replaceReviewedAmount(INVOICE_ID, request(true), MANAGER);

        var ordered = inOrder(messages, amounts, initialization, delivery);
        ordered.verify(messages).recordStatementReview(INVOICE_ID, OLD_OPERATION, NEW_OPERATION, MANAGER.getName());
        ordered.verify(initialization).reviewedRecipientProfile(invoice);
        ordered.verify(amounts).applyReviewedAmounts(invoice, items, 180000L);
        ordered.verify(initialization).ensureCommonPaymentRouteSelected(invoice, 180000L, 5L);
        ordered.verify(delivery).enqueueReviewedRevision(INVOICE_ID, prelude, previous);
        verify(delivery, times(1)).enqueueReviewedRevision(INVOICE_ID, prelude, previous);
        verify(amounts, times(1)).applyReviewedAmounts(invoice, items, 180000L);
        verify(visibility, times(2)).ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    @ParameterizedTest
    @ValueSource(strings = {"statement", "operation", "previousAmount", "currentAmount", "invoiceAmount", "unavailable", "missing"})
    void rejectsOutdatedOrUnreviewedConfirmationBeforeReservingOrEnqueuing(String changed) {
        var review = new CommonInvoiceRepricingReview(OLD_OPERATION, 140000L, 180000L, null, null, true);
        var request = request(true);
        switch (changed) {
            case "statement" -> request = request(false);
            case "operation" -> request = new CommonInvoiceRepricingRequest("bad-review-invoice:272:0", 140000, 180000, true);
            case "previousAmount" -> request = new CommonInvoiceRepricingRequest(OLD_OPERATION, 120000, 180000, true);
            case "currentAmount" -> request = new CommonInvoiceRepricingRequest(OLD_OPERATION, 140000, 200000, true);
            case "invoiceAmount" -> invoice.setAmountKopecks(160000L);
            case "unavailable" -> review = new CommonInvoiceRepricingReview(OLD_OPERATION, 140000L, 180000L, null, null, false);
            case "missing" -> review = null;
            default -> throw new AssertionError(changed);
        }
        when(details.repricingReview(invoice, items, null)).thenReturn(review);
        var confirmation = request;

        assertConflict(() -> service.replaceReviewedAmount(INVOICE_ID, confirmation, MANAGER));

        verify(messages, never()).recordStatementReview(anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(amounts, initialization);
        verify(delivery, never()).enqueueReviewedRevision(anyLong(), any(), any());
    }

    @Test
    void activeReceiptReconciliationPreventsReplacement() {
        prepareReview();
        prepareSnapshot();
        when(messages.recordStatementReview(INVOICE_ID, OLD_OPERATION, NEW_OPERATION, MANAGER.getName())).thenReturn(false);

        assertConflict(() -> service.replaceReviewedAmount(INVOICE_ID, request(true), MANAGER));

        verifyNoInteractions(amounts, initialization);
        verify(delivery, never()).enqueueReviewedRevision(anyLong(), any(), any());
    }

    @Test
    void insufficientRecipientCapacityAbortsBeforeEnqueue() {
        prepareReview();
        prepareSnapshot();
        when(messages.recordStatementReview(INVOICE_ID, OLD_OPERATION, NEW_OPERATION, MANAGER.getName())).thenReturn(true);
        when(initialization.reviewedRecipientProfile(invoice)).thenReturn(5L);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient recipient capacity"))
                .when(initialization).ensureCommonPaymentRouteSelected(invoice, 180000L, 5L);

        assertConflict(() -> service.replaceReviewedAmount(INVOICE_ID, request(true), MANAGER));

        verify(delivery, never()).enqueueReviewedRevision(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"number", "name", "source", "route"})
    void changedRecipientCausesConflictForTransactionRollback(String changed) {
        prepareReview();
        var previous = prepareSnapshot();
        when(messages.recordStatementReview(INVOICE_ID, OLD_OPERATION, NEW_OPERATION, MANAGER.getName())).thenReturn(true);
        when(delivery.telegramCopyTransferNumber(invoice))
                .thenReturn(changed.equals("number") ? "+79000000006" : RECIPIENT_NUMBER);
        doAnswer(call -> {
            switch (changed) {
                case "name" -> invoice.setPaymentRouteManualRecipient("Another recipient");
                case "source" -> invoice.setPaymentRouteManualSource(ManualPaymentSource.MANUAL_TASK);
                case "route" -> invoice.setPaymentRouteType("BANK_LINK");
                default -> { }
            }
            return null;
        }).when(delivery).enqueueReviewedRevision(INVOICE_ID, prelude, previous);

        assertConflict(() -> service.replaceReviewedAmount(INVOICE_ID, request(true), MANAGER));
    }

    private void prepareReview() {
        when(details.repricingReview(invoice, items, null)).thenReturn(
                new CommonInvoiceRepricingReview(OLD_OPERATION, 140000L, 180000L, null, null, true));
    }

    private PreparedCommonInvoiceMessage prepareSnapshot() {
        var previous = new PreparedCommonInvoiceMessage(INVOICE_ID, null, "whatsapp_test", "test-group",
                "Previous invoice", RECIPIENT_NUMBER, false, false, false, OLD_OPERATION, false, null, 140000L);
        when(messages.snapshot(OLD_OPERATION)).thenReturn(previous);
        return previous;
    }

    private CommonInvoiceRepricingRequest request(boolean checked) {
        return new CommonInvoiceRepricingRequest(OLD_OPERATION, 140000L, 180000L, checked);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
