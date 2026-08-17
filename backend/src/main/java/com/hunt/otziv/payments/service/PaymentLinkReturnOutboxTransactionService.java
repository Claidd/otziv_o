package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.repository.PaymentLinkReturnOutboxRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentLinkReturnOutboxTransactionService {

    private final PaymentLinkReturnOutboxRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentLinkReturnOutboxRepository.Claim> claimNext() {
        return repository.lockNextDueId()
                .flatMap(id -> repository.claim(id, UUID.randomUUID().toString()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(PaymentLinkReturnOutboxRepository.Claim claim) {
        if (!repository.markSucceeded(claim)) {
            throw new IllegalStateException("Return outbox success fencing failed");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(PaymentLinkReturnOutboxRepository.Claim claim, RuntimeException failure) {
        long seconds = Math.min(1800L, Math.max(5L, 5L << Math.min(8, claim.attemptCount())));
        String message = failure == null ? "unknown"
                : failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        if (!repository.markRetry(claim, LocalDateTime.now().plusSeconds(seconds), message)) {
            throw new IllegalStateException("Return outbox retry fencing failed");
        }
    }
}
