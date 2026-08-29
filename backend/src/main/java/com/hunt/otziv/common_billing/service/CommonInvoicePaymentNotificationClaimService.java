package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository.Delivery;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short fenced transactions around common-invoice notification delivery. */
@Service
public class CommonInvoicePaymentNotificationClaimService {

    private static final Duration MIN_LEASE = Duration.ofSeconds(5);
    private static final Duration MAX_LEASE = Duration.ofMinutes(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(6);

    private final CommonInvoicePaymentNotificationOutboxRepository repository;
    private final Duration leaseDuration;
    private final Supplier<String> tokenSupplier;
    private final String processingOwner;

    @Autowired
    public CommonInvoicePaymentNotificationClaimService(
            CommonInvoicePaymentNotificationOutboxRepository repository,
            @Value("${common-billing.payment-notifications.lease-duration:PT2M}")
            Duration leaseDuration
    ) {
        this(
                repository,
                leaseDuration,
                () -> UUID.randomUUID().toString(),
                "common-invoice-notification-" + UUID.randomUUID()
        );
    }

    CommonInvoicePaymentNotificationClaimService(
            CommonInvoicePaymentNotificationOutboxRepository repository,
            Duration leaseDuration,
            Supplier<String> tokenSupplier,
            String processingOwner
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.leaseDuration = boundedLease(leaseDuration);
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        this.processingOwner = identifier(processingOwner, 128, "processingOwner");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Optional<Delivery> tryClaim(long deliveryId) {
        if (deliveryId <= 0) {
            return Optional.empty();
        }
        return repository.tryAcquire(
                deliveryId,
                uuid(tokenSupplier.get()),
                processingOwner,
                leaseDuration
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markSent(Delivery delivery) {
        Delivery valid = valid(delivery);
        if (valid.kind() == CommonInvoicePaymentNotificationOutboxRepository.NotificationKind.CLIENT) {
            // The provider call already succeeded. Even if the invoice changed
            // concurrently, finalize the outbox row to avoid a duplicate send.
            repository.markClientInvoiceNotified(valid.invoiceId());
        }
        return repository.markSent(valid.deliveryId(), valid.processingToken());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markSkipped(Delivery delivery, String reason) {
        Delivery valid = valid(delivery);
        return repository.markSkipped(
                valid.deliveryId(),
                valid.processingToken(),
                identifier(reason, 512, "reason")
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markFailed(Delivery delivery, String error) {
        Delivery valid = valid(delivery);
        String cleanError = identifier(error, 512, "error");
        Duration delay = retryDelay(valid.attemptCount());
        if (valid.kind() == CommonInvoicePaymentNotificationOutboxRepository.NotificationKind.CLIENT) {
            repository.markClientInvoiceFailed(valid.invoiceId(), cleanError);
        }
        return repository.markFailed(
                valid.deliveryId(),
                valid.processingToken(),
                cleanError,
                delay
        );
    }

    private Duration retryDelay(int attemptCount) {
        int exponent = Math.max(0, Math.min(8, attemptCount - 1));
        Duration candidate = Duration.ofMinutes(1L << exponent);
        return candidate.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : candidate;
    }

    private Delivery valid(Delivery delivery) {
        if (delivery == null || delivery.deliveryId() <= 0 || delivery.invoiceId() <= 0) {
            throw new IllegalArgumentException("Common invoice notification claim is invalid");
        }
        uuid(delivery.processingToken());
        return delivery;
    }

    private Duration boundedLease(Duration candidate) {
        if (candidate == null
                || candidate.compareTo(MIN_LEASE) < 0
                || candidate.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException(
                    "Common invoice notification lease must be between PT5S and PT30M"
            );
        }
        return candidate;
    }

    private String uuid(String candidate) {
        try {
            return UUID.fromString(candidate).toString();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Common invoice notification token is not a UUID");
        }
    }

    private String identifier(String candidate, int maxLength, String field) {
        String normalized = candidate == null ? "" : candidate.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
