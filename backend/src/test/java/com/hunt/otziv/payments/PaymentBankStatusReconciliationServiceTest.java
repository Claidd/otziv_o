package com.hunt.otziv.payments;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentBankReconciliationCandidateView;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentBankStatusReconciliationService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentBankStatusReconciliationServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private PaymentLinkService paymentLinkService;
    @InjectMocks
    private PaymentBankStatusReconciliationService service;

    @Test
    void checksEveryStaleBankPaymentCandidate() {
        LocalDateTime now = LocalDateTime.now();
        when(paymentLinkRepository.findStatusBankReconciliationCandidates(
                any(), any(), any(), any(Pageable.class)
        )).thenReturn(List.of(candidate(1024L, null, now.minusMinutes(2))));
        when(paymentLinkRepository.findCancelBankReconciliationCandidates(
                any(), any(), any(Pageable.class)
        )).thenReturn(List.of(
                candidate(4095L, now.minusMinutes(10), now.minusMinutes(20)),
                candidate(1024L, null, now.minusMinutes(2))
        ));
        when(paymentLinkService.reconcileBankLink(eq(1024L), any())).thenReturn(true);

        service.reconcileStaleBankPayments();

        ArgumentCaptor<Collection> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(paymentLinkRepository).findStatusBankReconciliationCandidates(
                statuses.capture(),
                any(),
                any(),
                any(Pageable.class)
        );
        verify(paymentLinkRepository).findCancelBankReconciliationCandidates(
                any(),
                any(),
                any(Pageable.class)
        );
        assertTrue(statuses.getValue().contains(PaymentLinkStatus.NEEDS_RECONCILIATION));
        assertTrue(statuses.getValue().contains(PaymentLinkStatus.PARTIAL_REVERSED));
        assertTrue(statuses.getValue().contains(PaymentLinkStatus.PARTIAL_REFUNDED));
        verify(paymentLinkService).reconcileBankLink(eq(1024L), any());
        verify(paymentLinkService).reconcileBankLink(eq(4095L), any());
    }

    private PaymentBankReconciliationCandidateView candidate(
            Long id,
            LocalDateTime attemptedAt,
            LocalDateTime updatedAt
    ) {
        return new PaymentBankReconciliationCandidateView() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public LocalDateTime getAttemptedAt() {
                return attemptedAt;
            }

            @Override
            public LocalDateTime getUpdatedAt() {
                return updatedAt;
            }
        };
    }
}
