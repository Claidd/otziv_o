package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractorRewardRepairClaimServiceTest {

    @Mock private ContractorRewardRepairClaimRepository repository;

    @Test
    void activeLeaseIsSkippedAndExpiredLeaseCanBeReclaimedAtomically() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 20, 0);
        ContractorRewardRepairClaimService service = new ContractorRewardRepairClaimService(repository);
        when(repository.claim(
                org.mockito.ArgumentMatchers.eq(1L), anyString(),
                org.mockito.ArgumentMatchers.eq(now), any()
        )).thenReturn(0);
        when(repository.claim(
                org.mockito.ArgumentMatchers.eq(2L), anyString(),
                org.mockito.ArgumentMatchers.eq(now), any()
        )).thenReturn(1);

        assertThat(service.tryClaim(1L, now)).isEmpty();
        assertThat(service.tryClaim(2L, now)).isPresent();
    }
}
