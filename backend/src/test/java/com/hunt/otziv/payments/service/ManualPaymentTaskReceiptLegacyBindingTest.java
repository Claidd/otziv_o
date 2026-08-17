package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.service.ManualPaymentTaskContractorReservationService;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ManualPaymentTaskReceiptLegacyBindingTest {

    private ManualPaymentTaskLedgerService ledgerService;
    private ManualPaymentTaskContractorReservationService reservationService;
    private PaymentLinkRepository paymentLinkRepository;
    private CommonInvoiceRepository commonInvoiceRepository;
    private ManualPaymentTaskReceiptIntegrationService service;

    @BeforeEach
    void setUp() {
        ledgerService = mock(ManualPaymentTaskLedgerService.class);
        reservationService = mock(ManualPaymentTaskContractorReservationService.class);
        paymentLinkRepository = mock(PaymentLinkRepository.class);
        commonInvoiceRepository = mock(CommonInvoiceRepository.class);
        service = new ManualPaymentTaskReceiptIntegrationService(
                ledgerService,
                reservationService,
                paymentLinkRepository,
                commonInvoiceRepository
        );
    }

    @Test
    void paymentLinkCandidateAcceptsCurrentTaskGenerationForExplicitLegacyBinding() {
        PaymentLink link = paymentLink("LEGACY-128");
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 128L, "LEGACY-128");
        when(ledgerService.candidateForSource(source)).thenReturn(Optional.of(snapshot(source, 9L)));

        ManualPaymentTaskRouteSnapshot candidate = service.candidate(link).orElseThrow();

        assertEquals(9L, candidate.taskGeneration());
        assertEquals("TASK:16:9", candidate.candidateKey());
    }

    @Test
    void commonInvoiceCandidateAcceptsCurrentTaskGenerationForExplicitLegacyBinding() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(91L);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.MANUAL_TASK);
        invoice.setPaymentRouteManualTaskId(16L);
        invoice.setPaymentRouteManualTaskGeneration(3L);
        invoice.setPaymentRouteManualTaskSourceGeneration("LEGACY-91");
        invoice.setPaymentRouteAmountKopecks(250_000L);
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 91L, "LEGACY-91");
        when(ledgerService.candidateForSource(source)).thenReturn(Optional.of(snapshot(source, 9L)));

        ManualPaymentTaskRouteSnapshot candidate = service.candidate(invoice).orElseThrow();

        assertEquals(9L, candidate.taskGeneration());
        assertEquals("TASK:16:9", candidate.candidateKey());
    }

    @Test
    void nonLegacyCandidateStillRejectsGenerationMismatch() {
        PaymentLink link = paymentLink("route-128");
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 128L, "route-128");
        when(ledgerService.candidateForSource(source)).thenReturn(Optional.of(snapshot(source, 9L)));

        assertThrows(ResponseStatusException.class, () -> service.candidate(link));
    }

    @Test
    void partialCommonRouteKeepsFullExposureButMarksUnexpectedPaidEvidenceForAttention() {
        ManualPaymentTask task = task(16L);
        CommonInvoice invoice = pendingCommonInvoice(91L, 16L, 300_000L, 200_000L, 150_000L);
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 91L, "LEGACY-91");
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(source, 9L, 200_000L);
        var locks = new ManualPaymentTaskReceiptIntegrationService.LegacySourceLocks(
                List.of(source), ContractorAllocationMode.SHADOW);
        when(ledgerService.bindPendingLegacyReservations(task, "owner"))
                .thenReturn(List.of(snapshot));
        when(commonInvoiceRepository.findByIdForUpdate(91L)).thenReturn(Optional.of(invoice));

        service.bindPendingLegacyReservations(task, "owner", locks);

        assertEquals(CommonInvoiceStatus.NEEDS_ATTENTION, invoice.getStatus());
        assertEquals(ManualPaymentTaskStatus.NEEDS_ATTENTION, task.getStatus());
        assertEquals(true, task.isNeedsReconciliation());
        verify(reservationService).remediateLegacy(
                invoice, snapshot, ContractorAllocationMode.SHADOW);
        verify(commonInvoiceRepository).save(invoice);
    }

    @Test
    void impossibleCommonRouteArithmeticFailsBeforeContractorRemediation() {
        ManualPaymentTask task = task(16L);
        CommonInvoice invoice = pendingCommonInvoice(92L, 16L, 300_000L, 200_000L, 50_000L);
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.COMMON_INVOICE, 92L, "LEGACY-92");
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(source, 9L, 200_000L);
        var locks = new ManualPaymentTaskReceiptIntegrationService.LegacySourceLocks(
                List.of(source), ContractorAllocationMode.SHADOW);
        when(ledgerService.bindPendingLegacyReservations(task, "owner"))
                .thenReturn(List.of(snapshot));
        when(commonInvoiceRepository.findByIdForUpdate(92L)).thenReturn(Optional.of(invoice));

        assertThrows(ResponseStatusException.class, () ->
                service.bindPendingLegacyReservations(task, "owner", locks));

        verify(reservationService, never()).remediateLegacy(
                invoice, snapshot, ContractorAllocationMode.SHADOW);
        verify(commonInvoiceRepository, never()).save(invoice);
    }

    private PaymentLink paymentLink(String sourceGeneration) {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(16L);
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setAmountKopecks(250_000L);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setManualPaymentTask(task);
        link.setManualTaskGeneration(3L);
        link.setManualTaskSourceGeneration(sourceGeneration);
        return link;
    }

    private ManualPaymentTaskRouteSnapshot snapshot(
            ManualPaymentTaskSourceRef source,
            long taskGeneration
    ) {
        return snapshot(source, taskGeneration, 250_000L);
    }

    private ManualPaymentTaskRouteSnapshot snapshot(
            ManualPaymentTaskSourceRef source,
            long taskGeneration,
            long amount
    ) {
        return new ManualPaymentTaskRouteSnapshot(
                16L,
                taskGeneration,
                source,
                "TASK:16:" + taskGeneration,
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK,
                null,
                "Внешний получатель",
                ManualPaymentType.MOBILE_BANK,
                "+79990000000",
                "Наталья",
                null,
                null,
                amount,
                null,
                ""
        );
    }

    private ManualPaymentTask task(Long id) {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(id);
        task.setGeneration(9L);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        return task;
    }

    private CommonInvoice pendingCommonInvoice(
            Long invoiceId,
            Long taskId,
            long amount,
            long routeAmount,
            long paid
    ) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(invoiceId);
        invoice.setAmountKopecks(amount);
        invoice.setPaidKopecks(paid);
        invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        invoice.setPaymentRouteManualSource(ManualPaymentSource.MANUAL_TASK);
        invoice.setPaymentRouteManualTaskId(taskId);
        invoice.setPaymentRouteManualTaskGeneration(3L);
        invoice.setPaymentRouteManualTaskSourceGeneration("LEGACY-" + invoiceId);
        invoice.setPaymentRouteSelectedAt(java.time.LocalDateTime.now().minusDays(1));
        invoice.setPaymentRouteAmountKopecks(routeAmount);
        return invoice;
    }
}
