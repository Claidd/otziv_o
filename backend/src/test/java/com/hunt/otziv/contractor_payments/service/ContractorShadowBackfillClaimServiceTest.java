package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorShadowBackfillClaim;
import com.hunt.otziv.contractor_payments.repository.ContractorShadowBackfillClaimRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContractorShadowBackfillClaimServiceTest {

    private final ContractorShadowBackfillClaimRepository repository =
            mock(ContractorShadowBackfillClaimRepository.class);
    private final ContractorShadowBackfillClaimService service =
            new ContractorShadowBackfillClaimService(repository);

    @Test
    void expiredLeaseCanBeClaimedByAnotherNode() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 10, 0);
        when(repository.claim(eq("PAYMENT_LINK:17"), any(), eq(now), eq(now.plusMinutes(5))))
                .thenReturn(1);

        Optional<String> token = service.tryClaim("PAYMENT_LINK", 17L, now);

        assertThat(token).isPresent();
        verify(repository).insertIfMissing("PAYMENT_LINK:17", "PAYMENT_LINK", 17L);
    }

    @Test
    void failureReleasesLeaseAndPersistsSanitizedBackoff() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 10, 0);
        ContractorShadowBackfillClaim claim = new ContractorShadowBackfillClaim();
        claim.setClaimKey("COMMON_INVOICE:18");
        claim.setClaimToken("token");
        claim.setLeaseUntil(now.plusMinutes(5));
        claim.setRetryAttempts(3);
        when(repository.findByClaimKeyForUpdate("COMMON_INVOICE:18")).thenReturn(Optional.of(claim));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.failed(
                "COMMON_INVOICE", 18L, "token",
                new IllegalArgumentException("sensitive details"), now
        );

        assertThat(claim.getClaimToken()).isNull();
        assertThat(claim.getLeaseUntil()).isNull();
        assertThat(claim.getRetryAttempts()).isEqualTo(4);
        assertThat(claim.getNextRetryAt()).isEqualTo(now.plusSeconds(16));
        assertThat(claim.getLastErrorCode()).isEqualTo("IllegalArgumentException");
    }
}
