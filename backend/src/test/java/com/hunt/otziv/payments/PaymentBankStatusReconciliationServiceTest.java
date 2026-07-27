package com.hunt.otziv.payments;

import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentBankStatusReconciliationService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import static org.mockito.ArgumentMatchers.any;
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
        when(paymentLinkRepository.findBankReconciliationCandidateIds(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(1024L, 4095L));
        when(paymentLinkService.reconcileBankLink(1024L)).thenReturn(true);

        service.reconcileStaleBankPayments();

        verify(paymentLinkService).reconcileBankLink(1024L);
        verify(paymentLinkService).reconcileBankLink(4095L);
    }
}
