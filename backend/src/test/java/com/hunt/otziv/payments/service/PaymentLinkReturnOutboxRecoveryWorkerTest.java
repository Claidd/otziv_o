package com.hunt.otziv.payments.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;

import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkReturnOutboxRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentLinkReturnOutboxRecoveryWorkerTest {

    @Mock
    private PaymentLinkReturnOutboxTransactionService transactions;

    @Mock
    private ContractorPaymentShadowService shadowService;

    @Mock
    private PaymentReturnOrderRecoveryService recoveryService;

    @Mock
    private ScheduledClientMessageService clientMessageService;

    @Test
    void successfulAccountingThenRecoveryQueuesClientNotification() {
        PaymentLinkReturnOutboxRepository.Claim claim = new PaymentLinkReturnOutboxRepository.Claim(
                11L, 128L, 7L, "REFUNDED", "token", 1);
        when(transactions.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        when(recoveryService.reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        128L, PaymentLinkStatus.REFUNDED
                )
        )).thenReturn(Optional.of(42L));
        when(clientMessageService.enqueuePaymentReminderAfterFullReturn(42L)).thenReturn(true);

        new PaymentLinkReturnOutboxWorker(
                transactions,
                shadowService,
                recoveryService,
                clientMessageService
        ).processDue();

        verify(shadowService).reconcilePaymentLinkId(128L);
        verify(recoveryService).reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        128L, PaymentLinkStatus.REFUNDED
                )
        );
        verify(recoveryService).createReplacementPaymentRoute(42L);
        verify(clientMessageService).enqueuePaymentReminderAfterFullReturn(42L);
        verify(recoveryService).completeManualReturnFollowUp(128L);
        verify(transactions).succeeded(claim);
    }

    @Test
    void failedFollowUpKeepsManualResolutionInDurableRetryAndDoesNotCloseIssue() {
        PaymentLinkReturnOutboxRepository.Claim claim = new PaymentLinkReturnOutboxRepository.Claim(
                12L, 129L, 8L, "REFUNDED", "token-2", 2);
        RuntimeException failure = new IllegalStateException("message outbox unavailable");
        when(transactions.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        when(recoveryService.reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        129L, PaymentLinkStatus.REFUNDED
                )
        )).thenReturn(Optional.of(43L));
        doThrow(failure).when(clientMessageService).enqueuePaymentReminderAfterFullReturn(43L);

        new PaymentLinkReturnOutboxWorker(
                transactions,
                shadowService,
                recoveryService,
                clientMessageService
        ).processDue();

        verify(transactions).retry(claim, failure);
        verify(transactions, never()).succeeded(claim);
        verify(recoveryService, never()).completeManualReturnFollowUp(129L);
    }
}
