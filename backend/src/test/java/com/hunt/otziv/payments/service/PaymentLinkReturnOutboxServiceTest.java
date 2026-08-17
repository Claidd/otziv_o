package com.hunt.otziv.payments.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkReturnOutboxRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentLinkReturnOutboxServiceTest {

    @Mock
    private PaymentLinkReturnOutboxRepository repository;

    @Mock
    private PaymentLinkReturnOutboxTransactionService transactions;

    @Mock
    private ContractorPaymentShadowService shadowService;

    @Test
    void terminalReturnIsEnqueuedWithExactSourceSnapshot() {
        PaymentLinkReturnOutboxService service = new PaymentLinkReturnOutboxService(repository);
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setRowVersion(7L);
        link.setStatus(PaymentLinkStatus.REFUNDED);

        service.enqueue(link);

        verify(repository).enqueue(128L, 7L, "REFUNDED");
    }

    @Test
    void ordinaryNonReturnStatusDoesNotCreateWork() {
        PaymentLinkReturnOutboxService service = new PaymentLinkReturnOutboxService(repository);
        PaymentLink link = new PaymentLink();
        link.setId(128L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);

        service.enqueue(link);

        verify(repository, never()).enqueue(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void bankCancellationIsQueuedAndRecoveryLaterChecksWhetherMoneyWasSettled() {
        PaymentLinkReturnOutboxService service = new PaymentLinkReturnOutboxService(repository);
        PaymentLink link = new PaymentLink();
        link.setId(129L);
        link.setRowVersion(3L);
        link.setStatus(PaymentLinkStatus.CANCELED);

        service.enqueue(link);

        verify(repository).enqueue(129L, 3L, "CANCELED");
    }

    @Test
    void workerMarksClaimOnlyAfterIdempotentReconciliationSucceeds() {
        PaymentLinkReturnOutboxRepository.Claim claim = new PaymentLinkReturnOutboxRepository.Claim(
                1L, 128L, 7L, "REFUNDED", "token", 1);
        when(transactions.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        PaymentLinkReturnOutboxWorker worker = new PaymentLinkReturnOutboxWorker(
                transactions, shadowService);

        worker.processDue();

        verify(shadowService).reconcilePaymentLinkId(128L);
        verify(transactions).succeeded(claim);
        verify(transactions, never()).retry(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void workerReleasesClaimForDurableRetryAfterFailure() {
        PaymentLinkReturnOutboxRepository.Claim claim = new PaymentLinkReturnOutboxRepository.Claim(
                2L, 129L, 8L, "REVERSED", "token-2", 2);
        RuntimeException failure = new IllegalStateException("temporary");
        when(transactions.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        org.mockito.Mockito.doThrow(failure).when(shadowService).reconcilePaymentLinkId(129L);
        PaymentLinkReturnOutboxWorker worker = new PaymentLinkReturnOutboxWorker(
                transactions, shadowService);

        worker.processDue();

        verify(transactions).retry(claim, failure);
        verify(transactions, never()).succeeded(claim);
    }
}
