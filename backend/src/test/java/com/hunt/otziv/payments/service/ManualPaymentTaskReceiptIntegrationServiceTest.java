package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ManualPaymentTaskContractorReservationService;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSettlementCommand;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualPaymentTaskReceiptIntegrationServiceTest {

    private ManualPaymentTaskLedgerService ledgerService;
    private ManualPaymentTaskContractorReservationService contractorReservationService;
    private PaymentLinkRepository paymentLinkRepository;
    private CommonInvoiceRepository commonInvoiceRepository;
    private ManualPaymentTaskReceiptIntegrationService service;

    @BeforeEach
    void setUp() {
        ledgerService = mock(ManualPaymentTaskLedgerService.class);
        contractorReservationService = mock(ManualPaymentTaskContractorReservationService.class);
        paymentLinkRepository = mock(PaymentLinkRepository.class);
        commonInvoiceRepository = mock(CommonInvoiceRepository.class);
        service = new ManualPaymentTaskReceiptIntegrationService(
                ledgerService,
                contractorReservationService,
                paymentLinkRepository,
                commonInvoiceRepository
        );
    }

    @Test
    void externalTaskIsNotEncodedAsOwner() {
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK,
                null
        );

        ManualPaymentTaskReceiptIntegrationService.Destination destination = service.destination(snapshot);

        assertEquals(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK, destination.cashDestinationKind());
        assertEquals(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK, destination.taskTargetKind());
        assertNull(destination.recipientType());
        assertNull(destination.recipientProfileId());
    }

    @Test
    void contractorBoundTaskKeepsTaskAndExactProfile() {
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                77L
        );

        ManualPaymentTaskReceiptIntegrationService.Destination destination = service.destination(snapshot);

        assertEquals(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK, destination.cashDestinationKind());
        assertEquals(ContractorRecipientType.SPECIALIST, destination.recipientType());
        assertEquals(77L, destination.recipientProfileId());
        assertEquals(16L, destination.taskId());
        assertEquals(3L, destination.taskGeneration());
    }

    @Test
    void issuedExternalTaskPersistsShadowModeWithoutContractorAllocation() {
        PaymentLink link = taskLink();
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK, null);
        when(contractorReservationService.lockAccountingMode())
                .thenReturn(ContractorAllocationMode.SHADOW);
        when(ledgerService.reserveFirst(any())).thenReturn(Optional.of(snapshot));
        when(contractorReservationService.reserve(
                link, snapshot, ContractorAllocationMode.SHADOW)).thenReturn(null);

        assertEquals(Optional.of(snapshot), service.reserveForPaymentLink(link, 4L, 8L));

        assertEquals(ContractorAllocationMode.SHADOW, link.getManualActualAccountingMode());
        assertEquals(3L, link.getManualTaskGeneration());
        assertNull(link.getContractorAllocationId());
    }

    @Test
    void settleRedirectsWholeReservationWhenAnotherRecipientWasSelected() {
        PaymentLink link = taskLink();
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK,
                null
        );
        when(ledgerService.candidateForSource(snapshot.source())).thenReturn(Optional.of(snapshot));

        service.settle(link, "OWNER", 0L, "receipt-1", "manager", "paid elsewhere");

        ArgumentCaptor<ManualPaymentTaskSettlementCommand> captor =
                ArgumentCaptor.forClass(ManualPaymentTaskSettlementCommand.class);
        verify(ledgerService).settle(captor.capture());
        assertEquals(2_500_00L, captor.getValue().totalReservedAmountKopecks());
        assertEquals(0L, captor.getValue().taskAttributedAmountKopecks());
        assertEquals("OWNER", captor.getValue().selectedRecipientKey());
    }

    @Test
    void staleTaskCandidateCannotReceiveMoney() {
        PaymentLink link = taskLink();
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK,
                null
        );
        when(ledgerService.candidateForSource(snapshot.source())).thenReturn(Optional.of(snapshot));

        assertThrows(ResponseStatusException.class, () ->
                service.settle(link, "TASK:16:2", 2_500_00L, "receipt-1", "manager", "paid")
        );
    }

    @Test
    void releaseDoesNotReinterpretShadowAllocationUsingCurrentPhase() {
        PaymentLink link = taskLink();
        link.setContractorAllocationId(91L);
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                77L
        );
        when(ledgerService.candidateForSource(snapshot.source())).thenReturn(Optional.of(snapshot));

        service.release(link, "Перевод не поступил");

        var lockOrder = inOrder(contractorReservationService, ledgerService);
        lockOrder.verify(contractorReservationService).lockAccountingMode();
        lockOrder.verify(ledgerService).candidateForSource(snapshot.source());
        lockOrder.verify(ledgerService).release(any());
        lockOrder.verify(contractorReservationService).releaseLocked(
                91L,
                ContractorAllocationSourceType.PAYMENT_LINK,
                128L,
                "source-generation",
                ContractorAllocationStatus.RELEASED_UNPAID,
                "Перевод не поступил"
        );
        verify(contractorReservationService).releaseLocked(
                91L,
                ContractorAllocationSourceType.PAYMENT_LINK,
                128L,
                "source-generation",
                ContractorAllocationStatus.RELEASED_UNPAID,
                "Перевод не поступил"
        );
    }

    @Test
    void explicitLegacyBindingLocksSourceThenPhaseThenTaskBeforeContractorProfile() {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(16L);
        task.setGeneration(9L);
        PaymentLink link = taskLink();
        link.setManualTaskSourceGeneration("LEGACY-128");
        link.setManualTaskGeneration(3L);
        link.setReservedAmountKopecks(2_500_00L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setExpiresAt(LocalDateTime.now().plusHours(1));
        ManualPaymentTaskRouteSnapshot bound = new ManualPaymentTaskRouteSnapshot(
                16L,
                9L,
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                        128L,
                        "LEGACY-128"
                ),
                "TASK:16:9",
                ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                77L,
                "Специалист",
                ManualPaymentType.MOBILE_BANK,
                "+79990000000",
                "Наталья",
                null,
                null,
                2_500_00L,
                null,
                ""
        );
        when(ledgerService.pendingUnresolvedSources(16L)).thenReturn(List.of(bound.source()));
        when(paymentLinkRepository.findByIdForUpdate(128L)).thenReturn(Optional.of(link));
        when(contractorReservationService.lockAccountingMode())
                .thenReturn(com.hunt.otziv.contractor_payments.model.ContractorAllocationMode.SHADOW);
        when(ledgerService.bindPendingLegacyReservations(task, "admin"))
                .thenReturn(List.of(bound));
        when(contractorReservationService.remediateLegacy(
                link,
                bound,
                com.hunt.otziv.contractor_payments.model.ContractorAllocationMode.SHADOW
        )).thenReturn(92L);

        ManualPaymentTaskReceiptIntegrationService.LegacySourceLocks locks =
                service.lockLegacySourcesThenAccountingMode(16L);
        service.bindPendingLegacyReservations(task, "admin", locks);

        assertEquals(9L, link.getManualTaskGeneration());
        assertEquals(
                com.hunt.otziv.contractor_payments.model.ContractorAllocationMode.SHADOW,
                link.getManualActualAccountingMode()
        );
        assertEquals(92L, link.getContractorAllocationId());
        verify(paymentLinkRepository).save(link);
        var lockOrder = inOrder(
                ledgerService,
                paymentLinkRepository,
                contractorReservationService
        );
        lockOrder.verify(ledgerService).pendingUnresolvedSources(16L);
        lockOrder.verify(paymentLinkRepository).findByIdForUpdate(128L);
        lockOrder.verify(contractorReservationService).lockAccountingMode();
        lockOrder.verify(ledgerService).bindPendingLegacyReservations(task, "admin");
        lockOrder.verify(paymentLinkRepository).findByIdForUpdate(128L);
        lockOrder.verify(contractorReservationService).remediateLegacy(
                link,
                bound,
                com.hunt.otziv.contractor_payments.model.ContractorAllocationMode.SHADOW
        );
    }

    @Test
    void finalAttributionPreflightLocksTaskBeforeContractorProfiles() {
        PaymentLink link = taskLink();
        link.setManualActualOriginalCashDestinationKind(
                ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        link.setManualActualOriginalTaskId(16L);
        ManualPaymentTaskRouteSnapshot snapshot = snapshot(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST,
                77L
        );
        when(ledgerService.lockSourceTask(16L, snapshot.source())).thenReturn(snapshot);

        service.lockTaskForFinalAttribution(link);

        verify(ledgerService).lockSourceTask(16L, snapshot.source());
    }

    private PaymentLink taskLink() {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(16L);
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setAmountKopecks(2_500_00L);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setManualPaymentTask(task);
        link.setManualTaskSourceGeneration("source-generation");
        link.setManualTaskGeneration(3L);
        return link;
    }

    private ManualPaymentTaskRouteSnapshot snapshot(
            ManualPaymentTaskAccountingTargetKind kind,
            Long profileId
    ) {
        ManualPaymentTaskSourceRef source = new ManualPaymentTaskSourceRef(
                ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK,
                128L,
                "source-generation"
        );
        return new ManualPaymentTaskRouteSnapshot(
                16L,
                3L,
                source,
                "TASK:16:3",
                kind,
                profileId,
                kind == ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK ? "Внешний получатель" : "Специалист",
                ManualPaymentType.MOBILE_BANK,
                "+79990000000",
                "Наталья",
                null,
                null,
                2_500_00L,
                LocalDateTime.now(),
                "admin"
        );
    }
}
