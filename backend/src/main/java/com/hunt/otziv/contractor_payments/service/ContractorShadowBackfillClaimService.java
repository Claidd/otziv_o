package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorShadowBackfillClaim;
import com.hunt.otziv.contractor_payments.repository.ContractorShadowBackfillClaimRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContractorShadowBackfillClaimService {

    private final ContractorShadowBackfillClaimRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> tryClaim(String queueType, Long sourceId, LocalDateTime now) {
        String claimKey = key(queueType, sourceId);
        repository.insertIfMissing(claimKey, queueType, sourceId);
        String token = UUID.randomUUID().toString();
        return repository.claim(claimKey, token, now, now.plusMinutes(5)) == 1
                ? Optional.of(token)
                : Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(String queueType, Long sourceId, String token, LocalDateTime now) {
        ContractorShadowBackfillClaim claim = repository.findByClaimKeyForUpdate(key(queueType, sourceId))
                .orElse(null);
        if (claim == null || !Objects.equals(token, claim.getClaimToken())) {
            return;
        }
        claim.setCompletedAt(now);
        claim.setClaimToken(null);
        claim.setLeaseUntil(null);
        claim.setRetryAttempts(0);
        claim.setNextRetryAt(null);
        claim.setLastErrorCode(null);
        repository.save(claim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(
            String queueType,
            Long sourceId,
            String token,
            RuntimeException failure,
            LocalDateTime now
    ) {
        ContractorShadowBackfillClaim claim = repository.findByClaimKeyForUpdate(key(queueType, sourceId))
                .orElse(null);
        if (claim == null || !Objects.equals(token, claim.getClaimToken())) {
            return;
        }
        int attempts = Math.addExact(claim.getRetryAttempts(), 1);
        long delaySeconds = Math.min(21_600L, 1L << Math.min(14, attempts));
        claim.setRetryAttempts(attempts);
        claim.setNextRetryAt(now.plusSeconds(delaySeconds));
        claim.setClaimToken(null);
        claim.setLeaseUntil(null);
        String code = failure == null ? "RuntimeException" : failure.getClass().getSimpleName();
        claim.setLastErrorCode(code.length() <= 120 ? code : code.substring(0, 120));
        repository.save(claim);
    }

    public static String key(String queueType, Long sourceId) {
        if (queueType == null || queueType.isBlank() || sourceId == null || sourceId <= 0) {
            throw new IllegalArgumentException("Invalid contractor shadow backfill claim key");
        }
        return queueType + ":" + sourceId;
    }
}
