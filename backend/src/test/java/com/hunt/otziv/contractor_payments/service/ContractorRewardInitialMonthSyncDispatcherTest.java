package com.hunt.otziv.contractor_payments.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ContractorRewardInitialMonthSyncDispatcherTest {

    @Mock private ContractorPaymentProfileRepository profileRepository;
    @Mock private ContractorRewardInitialMonthSyncCoordinator coordinator;

    @Test
    void retriesEveryEnabledProfileWhoseCurrentMonthCoverageIsIncomplete() {
        ContractorPaymentBusinessClock clock = new ContractorPaymentBusinessClock(
                Clock.fixed(Instant.parse("2026-08-10T05:00:00Z"), ZoneId.of("UTC")),
                ZoneId.of("Asia/Irkutsk")
        );
        when(profileRepository.findEnabledIdsRequiringCurrentMonthSync(
                any(), any(Pageable.class)
        )).thenReturn(List.of(7L, 9L));
        ContractorRewardInitialMonthSyncDispatcher dispatcher =
                new ContractorRewardInitialMonthSyncDispatcher(profileRepository, clock, coordinator);

        dispatcher.synchronizeEnabledProfiles();

        verify(profileRepository).findEnabledIdsRequiringCurrentMonthSync(
                eq(LocalDate.of(2026, 8, 1).atStartOfDay()),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        );
        verify(coordinator).synchronizeSafely(7L);
        verify(coordinator).synchronizeSafely(9L);
    }
}
