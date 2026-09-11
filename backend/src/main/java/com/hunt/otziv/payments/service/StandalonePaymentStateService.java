package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.api.StandalonePaymentState;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StandalonePaymentStateService implements StandalonePaymentState {
    private final PaymentLinkRepository links;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasStartedPaymentWithLock(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("Order id must be positive");
        return links.findByOrderIdForUpdate(orderId).stream()
                .anyMatch(StandaloneBankPaymentPolicy::hasStartedProviderPayment);
    }
}
