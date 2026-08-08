package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContractorPaymentReconcileClaimServiceTest {

    private final ContractorPaymentAllocationRepository repository =
            mock(ContractorPaymentAllocationRepository.class);
    private final ContractorPaymentReconcileClaimService service =
            new ContractorPaymentReconcileClaimService(repository);

    @Test
    void expiredOrFreeLeaseCanBeAtomicallyRecovered() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 12, 0);
        when(repository.claimForReconciliation(eq(8L), any(), eq(now), eq(now.plusMinutes(5))))
                .thenReturn(1);

        Optional<String> token = service.tryClaim(8L, now);

        assertThat(token).isPresent();
        verify(repository).claimForReconciliation(8L, token.orElseThrow(), now, now.plusMinutes(5));
    }

    @Test
    void failureClearsLeaseAndAppliesBoundedBackoff() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 12, 0);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(9L);
        allocation.setReconcileClaimToken("token");
        allocation.setReconcileLeaseUntil(now.plusMinutes(5));
        allocation.setReconcileAttempts(2);
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(allocation));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.failed(9L, "token", new IllegalStateException("secret must not persist"), now);

        assertThat(allocation.getReconcileClaimToken()).isNull();
        assertThat(allocation.getReconcileLeaseUntil()).isNull();
        assertThat(allocation.getReconcileAttempts()).isEqualTo(3);
        assertThat(allocation.getReconcileNextRetryAt()).isEqualTo(now.plusSeconds(8));
        assertThat(allocation.getReconcileLastErrorCode()).isEqualTo("IllegalStateException");
    }
}
