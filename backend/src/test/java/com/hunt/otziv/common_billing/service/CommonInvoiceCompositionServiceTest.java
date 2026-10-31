package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService.FrozenCommonRouteAction;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CommonInvoiceCompositionServiceTest {
    final CommonInvoiceInitializationService initialization = mock(CommonInvoiceInitializationService.class);
    final CommonInvoicePaymentRefRepository refs = mock(CommonInvoicePaymentRefRepository.class);
    final ContractorPaymentLiveRoutingService routing = mock(ContractorPaymentLiveRoutingService.class);
    final ManualPaymentTaskReceiptIntegrationService tasks = mock(ManualPaymentTaskReceiptIntegrationService.class);
    final CommonInvoiceCompositionService service = new CommonInvoiceCompositionService(initialization, refs);
    CommonInvoice invoice;
    CommonInvoiceOrder item;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(initialization, "contractorPaymentLiveRoutingService", routing);
        ReflectionTestUtils.setField(initialization, "taskReceiptIntegrationService", tasks);
        doCallRealMethod().when(initialization).compositionReservationBlockReason(any(), anyBoolean());
        doCallRealMethod().when(initialization).releaseUnusedCompositionReservation(any());
        invoice = new CommonInvoice(); invoice.setId(297L);
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError("review_approval_failed: order=27391; problem=old duplicate");
        item = new CommonInvoiceOrder(); item.setInvoice(invoice);
    }

    @Test void oldReviewFailureAllowsCompositionWithoutMarkingPaymentOrSending() {
        assertThat(service.previewBlockReason(invoice)).isNull();
        service.prepareChange(invoice, item);
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.NEEDS_ATTENTION);
        assertThat(invoice.getLastError()).contains("order=27391");
        assertThat(invoice.getPaidKopecks()).isZero();
        verifyNoInteractions(routing, tasks);
    }

    @ParameterizedTest
    @ValueSource(strings={"paid", "paidAt", "reported", "confirmed", "sent", "messageUnknown", "legacyUnknown", "bank",
            "financialError", "closed", "itemPaid", "itemUnpaid", "bankAttempt"})
    void financialEvidenceNeverReleasesReserveOrClearsRoute(String condition) {
        reserve();
        switch (condition) {
            case "paid" -> invoice.setPaidKopecks(1);
            case "paidAt" -> invoice.setPaidAt(LocalDateTime.now());
            case "reported" -> invoice.setClientReportedAt(LocalDateTime.now());
            case "confirmed" -> invoice.setManualConfirmedAt(LocalDateTime.now());
            case "sent" -> invoice.setSentAt(LocalDateTime.now());
            case "messageUnknown" -> invoice.setPaymentMessageOperationId("original-unknown-operation");
            case "legacyUnknown" -> when(initialization.hasUnresolvedLegacyMessage(invoice)).thenReturn(true);
            case "bank" -> invoice.setTbankPaymentId("original-payment");
            case "financialError" -> invoice.setLastError("payment_init_conflict");
            case "closed" -> invoice.setStatus(CommonInvoiceStatus.PAID);
            case "itemPaid" -> item.setPaid(true);
            case "itemUnpaid" -> item.setUnpaid(true);
            case "bankAttempt" -> {
                CommonInvoicePaymentRef ref = new CommonInvoicePaymentRef(); ref.setStatus("INIT_PREPARED");
                when(refs.findByInvoiceIdForUpdate(297L)).thenReturn(List.of(ref));
            }
        }
        assertThatThrownBy(() -> service.prepareChange(invoice, item)).isInstanceOf(ResponseStatusException.class);
        assertThat(invoice.getContractorAllocationId()).isEqualTo(7L);
        assertThat(invoice.getPaymentRouteSelectedAt()).isNotNull();
        verify(routing, never()).releaseCommonInvoiceRouteForReplacement(any(), anyString(), anyString());
        verifyNoInteractions(tasks);
    }

    @Test void unusedContractorReservationIsReleasedBeforeRouteIsCleared() {
        reserve();
        when(routing.frozenCommonRouteAction(297L, 7L)).thenReturn(FrozenCommonRouteAction.KEEP);
        when(routing.releaseCommonInvoiceRouteForReplacement(eq(invoice), anyString(), anyString())).thenAnswer(call -> {
            assertThat(invoice.getContractorAllocationId()).isEqualTo(7L);
            return true;
        });
        service.prepareChange(invoice, item);
        verify(routing).releaseCommonInvoiceRouteForReplacement(eq(invoice), anyString(), eq("LIVE_COMMON:COMPOSITION_CHANGED"));
        assertThat(invoice.getContractorAllocationId()).isNull();
        assertThat(invoice.getPaymentRouteSelectedAt()).isNull();
        assertThat(invoice.getPaymentRouteAmountKopecks()).isNull();
    }

    @Test void failedReleaseKeepsOriginalRouteAndAbortsComposition() {
        reserve();
        when(routing.frozenCommonRouteAction(297L, 7L)).thenReturn(FrozenCommonRouteAction.KEEP);
        assertThatThrownBy(() -> service.prepareChange(invoice, item)).isInstanceOf(ResponseStatusException.class);
        assertThat(invoice.getContractorAllocationId()).isEqualTo(7L);
        assertThat(invoice.getPaymentRouteSelectedAt()).isNotNull();
    }

    @Test void previewChecksReservationWithoutTakingCommandLocksOrReleasingIt() {
        reserve();
        when(routing.previewFrozenCommonRouteAction(297L, 7L)).thenReturn(FrozenCommonRouteAction.BLOCK_RECONCILIATION);
        assertThat(service.previewBlockReason(invoice)).contains("сверку резерва");
        verify(routing, never()).frozenCommonRouteAction(any(), any());
        verify(routing, never()).releaseCommonInvoiceRouteForReplacement(any(), anyString(), anyString());
        assertThat(invoice.getContractorAllocationId()).isEqualTo(7L);
    }

    @Test void manualTaskUsesItsOwnReservationLedgerAndPropagatesConflict() {
        reserve(); invoice.setPaymentRouteManualSource(ManualPaymentSource.MANUAL_TASK);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "received payment"))
                .when(tasks).release(eq(invoice), anyString());
        assertThatThrownBy(() -> service.prepareChange(invoice, item)).isInstanceOf(ResponseStatusException.class);
        assertThat(invoice.getContractorAllocationId()).isEqualTo(7L);
        verifyNoInteractions(routing);
    }

    private void reserve() {
        invoice.setContractorAllocationId(7L); invoice.setPaymentRouteType("CONTRACTOR");
        invoice.setPaymentRouteSelectedAt(LocalDateTime.now()); invoice.setPaymentRouteAmountKopecks(140000L);
    }
}
