package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.repository.PaymentSuccessNotificationRetryClaimRepository;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns the retry claim's short transactions; external I/O happens elsewhere. */
@Service
public class PaymentSuccessNotificationRetryClaimService {

    private static final Duration MIN_LEASE = Duration.ofSeconds(5);
    private static final Duration MAX_LEASE = Duration.ofMinutes(30);

    private final PaymentSuccessNotificationRetryClaimRepository repository;
    private final Duration leaseDuration;
    private final Supplier<String> tokenSupplier;
    private final String processingOwner;

    @Autowired
    public PaymentSuccessNotificationRetryClaimService(
            PaymentSuccessNotificationRetryClaimRepository repository,
            @Value("${otziv.payments.success-notification-retry.lease-duration:PT2M}")
            Duration leaseDuration
    ) {
        this(
                repository,
                leaseDuration,
                () -> UUID.randomUUID().toString(),
                "payment-notification-" + UUID.randomUUID()
        );
    }

    PaymentSuccessNotificationRetryClaimService(
            PaymentSuccessNotificationRetryClaimRepository repository,
            Duration leaseDuration,
            Supplier<String> tokenSupplier,
            String processingOwner
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.leaseDuration = requireBoundedLease(leaseDuration);
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        this.processingOwner = requireIdentifier(processingOwner, 128, "processingOwner");
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 10
    )
    public Optional<Claim> tryClaim(long paymentLinkId) {
        if (paymentLinkId <= 0) {
            return Optional.empty();
        }
        String token = requireUuid(tokenSupplier.get());
        if (!repository.lockRetryEligiblePaymentLink(paymentLinkId)) {
            return Optional.empty();
        }
        try {
            if (!repository.tryAcquire(
                    paymentLinkId,
                    token,
                    processingOwner,
                    leaseDuration
            )) {
                return Optional.empty();
            }
        } catch (DataIntegrityViolationException exception) {
            // The live link may have been archived between candidate selection
            // and claim insertion. There is no retryable work in that case.
            return Optional.empty();
        }
        return Optional.of(new Claim(paymentLinkId, token));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markSucceeded(Claim claim) {
        Claim valid = requireClaim(claim);
        if (!repository.lockPaymentLinkForFinalization(valid.paymentLinkId())
                || !repository.lockOwnedClaim(valid.paymentLinkId(), valid.processingToken())) {
            return false;
        }
        boolean updated = repository.markSucceeded(valid.paymentLinkId());
        repository.release(valid.paymentLinkId(), valid.processingToken());
        return updated;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean markFailed(Claim claim, String error) {
        Claim valid = requireClaim(claim);
        if (!repository.lockPaymentLinkForFinalization(valid.paymentLinkId())
                || !repository.lockOwnedClaim(valid.paymentLinkId(), valid.processingToken())) {
            return false;
        }
        boolean updated = repository.markFailed(
                valid.paymentLinkId(),
                requireIdentifier(error, 512, "error")
        );
        repository.release(valid.paymentLinkId(), valid.processingToken());
        return updated;
    }

    private Claim requireClaim(Claim claim) {
        if (claim == null || claim.paymentLinkId() <= 0) {
            throw new IllegalArgumentException("Payment notification claim is invalid");
        }
        return new Claim(claim.paymentLinkId(), requireUuid(claim.processingToken()));
    }

    private static Duration requireBoundedLease(Duration candidate) {
        if (candidate == null
                || candidate.compareTo(MIN_LEASE) < 0
                || candidate.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException(
                    "Payment notification retry lease must be between PT5S and PT30M"
            );
        }
        return candidate;
    }

    private String requireUuid(String candidate) {
        try {
            return UUID.fromString(candidate).toString();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Payment notification claim token is not a UUID");
        }
    }

    private String requireIdentifier(String candidate, int maxLength, String field) {
        String normalized = candidate == null ? "" : candidate.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    public record Claim(long paymentLinkId, String processingToken) {
    }
}
