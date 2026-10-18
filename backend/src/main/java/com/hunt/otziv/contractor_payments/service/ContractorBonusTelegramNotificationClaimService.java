package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.repository.ContractorBonusTelegramNotificationOutboxRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorBonusTelegramNotificationOutboxRepository.Delivery;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short, independent transactions fence each bonus notification attempt. */
@Service
@RequiredArgsConstructor
public class ContractorBonusTelegramNotificationClaimService {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(6);
    private final ContractorBonusTelegramNotificationOutboxRepository repository;
    private final String processingOwner = "contractor-bonus-" + UUID.randomUUID();

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Optional<Delivery> tryClaim(long adjustmentId) {
        if (adjustmentId <= 0) {
            return Optional.empty();
        }
        return repository.tryAcquire(
                adjustmentId, UUID.randomUUID().toString(), processingOwner, LEASE_DURATION
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markSent(Delivery delivery) {
        return repository.markSent(valid(delivery));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markSkipped(Delivery delivery, String reason) {
        return repository.markSkipped(valid(delivery), limit(reason));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markFailed(Delivery delivery, String error) {
        int exponent = Math.max(0, Math.min(8, delivery.attemptCount() - 1));
        Duration candidate = Duration.ofMinutes(1L << exponent);
        Duration delay = candidate.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : candidate;
        return repository.markFailed(valid(delivery), limit(error), delay);
    }

    private Delivery valid(Delivery delivery) {
        if (delivery == null || delivery.adjustmentId() <= 0 || delivery.attemptCount() <= 0) {
            throw new IllegalArgumentException("Invalid bonus Telegram notification claim");
        }
        UUID.fromString(delivery.processingToken());
        return delivery;
    }

    private String limit(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            text = "bonus_telegram_notification_failed";
        }
        return text.length() <= 512 ? text : text.substring(0, 512);
    }
}
