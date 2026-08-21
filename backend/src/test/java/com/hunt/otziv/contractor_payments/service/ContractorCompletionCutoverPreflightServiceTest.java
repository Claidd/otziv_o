package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.repository.ContractorCompletionCutoverPreflightRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

@ExtendWith(MockitoExtension.class)
class ContractorCompletionCutoverPreflightServiceTest {

    @Mock private ContractorPaymentProfileRepository profileRepository;
    @Mock private ContractorRewardRepairClaimRepository rewardRepairClaimRepository;
    @Mock private ZpRepository zpRepository;
    @Mock private ContractorCompletionCutoverPreflightRepository cutoverPreflightRepository;
    @Mock private ContractorPaymentBusinessClock businessClock;
    @InjectMocks private ContractorCompletionCutoverPreflightService service;

    private final LocalDate start = LocalDate.of(2026, 8, 7);
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 7, 12, 0);

    @BeforeEach
    void setUp() {
        lenient().when(businessClock.today()).thenReturn(LocalDate.of(2026, 8, 7));
        lenient().when(businessClock.now()).thenReturn(now);
        lenient().when(profileRepository.findEnabledIdsRequiringCurrentMonthSync(any(), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(rewardRepairClaimRepository.count()).thenReturn(0L);
        lenient().when(zpRepository.countActiveIncompatibleContractorRewardSources()).thenReturn(0L);
        lenient().when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(cutoverPreflightRepository.countActiveLegacyRewardCutoverConflicts(start))
                .thenReturn(0L);
    }

    @Test
    void onlyCurrentBusinessDateWithEveryQueueAndLegacyCheckCleanIsReady() {
        assertThat(service.readyForActivation(start)).isTrue();
        assertThat(service.readyForActivation(LocalDate.of(2026, 8, 6))).isFalse();
    }

    @Test
    void profileSyncRewardRepairAndLegacyOverlapEachFailClosed() {
        when(profileRepository.findEnabledIdsRequiringCurrentMonthSync(any(), any(Pageable.class)))
                .thenReturn(List.of(4L));
        assertThat(service.readyForActivation(start)).isFalse();

        when(profileRepository.findEnabledIdsRequiringCurrentMonthSync(any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(rewardRepairClaimRepository.count()).thenReturn(1L);
        assertThat(service.readyForActivation(start)).isFalse();

        when(rewardRepairClaimRepository.count()).thenReturn(0L);
        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(), any(Pageable.class)))
                .thenReturn(List.of(new Zp()));
        assertThat(service.readyForActivation(start)).isFalse();

        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(cutoverPreflightRepository.countActiveLegacyRewardCutoverConflicts(start)).thenReturn(1L);
        assertThat(service.readyForActivation(start)).isFalse();
    }

    @Test
    void currentSyncMarkerCannotHideIncompatibleSourceRoleFromActivation() {
        when(zpRepository.countActiveIncompatibleContractorRewardSources()).thenReturn(1L);

        assertThat(service.readyForActivation(start)).isFalse();

        verify(zpRepository, never()).findContractorRewardsNeedingGlobalRepair(any(), any(Pageable.class));
    }

    @Test
    void nullBlankUnknownAndUndatedLegacyEvidenceAreCutoverConflictsByQueryContract() throws Exception {
        Query query = ContractorCompletionCutoverPreflightRepository.class
                .getMethod("countActiveLegacyRewardCutoverConflicts", LocalDate.class)
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
                .contains(
                        "reward.zp_source IS NULL",
                        "TRIM(reward.zp_source) = ''",
                        "OR NOT (",
                        "CAST('ORDER_MANAGER_REWARD' AS BINARY)",
                        "CAST('ORDER_SPECIALIST_REWARD' AS BINARY)",
                        "CAST('PERFORMER_PRODUCT_REWARD' AS BINARY)",
                        "contractor_completion_reward_markers bridge_marker",
                        "bridge_marker.logical_source = CASE",
                        "THEN 'ORDER_COMPLETION_MANAGER'",
                        "THEN 'ORDER_COMPLETION_SPECIALIST'",
                        "THEN 'PERFORMER_PRODUCT_COMPLETION'",
                        "undated_review.review_publish_date IS NULL",
                        "undated_review.review_publish_date >= :startDate",
                        "ambiguous_task.bad_review_task_completed_date IS NULL",
                        "ambiguous_task.bad_review_task_completed_date >= :startDate"
                );
    }
}
