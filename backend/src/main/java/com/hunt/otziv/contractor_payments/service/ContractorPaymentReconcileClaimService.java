package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
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
public class ContractorPaymentReconcileClaimService {

    private final ContractorPaymentAllocationRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> tryClaim(Long allocationId, LocalDateTime now) {
        String token = UUID.randomUUID().toString();
        return repository.claimForReconciliation(allocationId, token, now, now.plusMinutes(5)) == 1
                ? Optional.of(token)
                : Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(Long allocationId, String token) {
        ContractorPaymentAllocation allocation = repository.findByIdForUpdate(allocationId).orElse(null);
        if (allocation == null || !Objects.equals(token, allocation.getReconcileClaimToken())) {
            return;
        }
        allocation.setReconcileClaimToken(null);
        allocation.setReconcileLeaseUntil(null);
        allocation.setReconcileAttempts(0);
        allocation.setReconcileNextRetryAt(null);
        allocation.setReconcileLastErrorCode(null);
        repository.save(allocation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long allocationId, String token, RuntimeException failure, LocalDateTime now) {
        ContractorPaymentAllocation allocation = repository.findByIdForUpdate(allocationId).orElse(null);
        if (allocation == null || !Objects.equals(token, allocation.getReconcileClaimToken())) {
            return;
        }
        int attempts = Math.addExact(allocation.getReconcileAttempts(), 1);
        long delaySeconds = Math.min(3_600L, 1L << Math.min(12, attempts));
        allocation.setReconcileAttempts(attempts);
        allocation.setReconcileNextRetryAt(now.plusSeconds(delaySeconds));
        allocation.setReconcileClaimToken(null);
        allocation.setReconcileLeaseUntil(null);
        String code = failure == null ? "RuntimeException" : failure.getClass().getSimpleName();
        allocation.setReconcileLastErrorCode(code.length() <= 120 ? code : code.substring(0, 120));
        repository.save(allocation);
    }
}
