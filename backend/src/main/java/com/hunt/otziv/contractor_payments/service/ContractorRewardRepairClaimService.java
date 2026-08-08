package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorRewardRepairClaim;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import java.time.Duration;
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
public class ContractorRewardRepairClaimService {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private final ContractorRewardRepairClaimRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> tryClaim(Long sourceZpId, LocalDateTime now) {
        if (sourceZpId == null) {
            return Optional.empty();
        }
        repository.insertIfMissing(sourceZpId);
        String token = UUID.randomUUID().toString();
        return repository.claim(sourceZpId, token, now, now.plus(LEASE)) == 1
                ? Optional.of(token)
                : Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(Long sourceZpId, String token) {
        ContractorRewardRepairClaim claim = repository.findBySourceZpIdForUpdate(sourceZpId).orElse(null);
        if (claim != null && Objects.equals(claim.getClaimToken(), token)) {
            repository.delete(claim);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long sourceZpId, String token, RuntimeException failure, LocalDateTime now) {
        ContractorRewardRepairClaim claim = repository.findBySourceZpIdForUpdate(sourceZpId).orElse(null);
        if (claim == null || !Objects.equals(claim.getClaimToken(), token)) {
            return;
        }
        int attempts = Math.addExact(claim.getRetryAttempts(), 1);
        long delaySeconds = Math.min(3_600L, 1L << Math.min(12, attempts));
        claim.setRetryAttempts(attempts);
        claim.setNextRetryAt(now.plusSeconds(delaySeconds));
        claim.setLeaseUntil(null);
        claim.setClaimToken(null);
        // Never persist provider messages, SQL, requisites or arbitrary PII.
        String code = failure == null ? "RuntimeException" : failure.getClass().getSimpleName();
        claim.setLastErrorCode(code.length() <= 120 ? code : code.substring(0, 120));
        repository.save(claim);
    }
}
