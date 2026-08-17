package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.repository.ContractorCompletionCutoverPreflightRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardRepairClaimRepository;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Financial preflight that must be clean before the one-way cutover is latched. */
@Service
@RequiredArgsConstructor
public class ContractorCompletionCutoverPreflightService {

    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorRewardRepairClaimRepository rewardRepairClaimRepository;
    private final ZpRepository zpRepository;
    private final ContractorCompletionCutoverPreflightRepository cutoverPreflightRepository;
    private final ContractorPaymentBusinessClock businessClock;

    @Transactional(readOnly = true)
    public boolean readyForActivation(LocalDate startDate) {
        if (startDate == null || !startDate.equals(businessClock.today())) {
            return false;
        }
        LocalDate monthStart = businessClock.today().withDayOfMonth(1);
        if (!profileRepository.findEnabledIdsRequiringCurrentMonthSync(
                monthStart.atStartOfDay(),
                PageRequest.of(0, 1)
        ).isEmpty()) {
            return false;
        }
        if (rewardRepairClaimRepository.count() > 0L) {
            return false;
        }
        if (zpRepository.countActiveIncompatibleContractorRewardSources() > 0L) {
            return false;
        }
        if (!zpRepository.findContractorRewardsNeedingGlobalRepair(
                businessClock.now(),
                PageRequest.of(0, 1)
        ).isEmpty()) {
            return false;
        }
        return cutoverPreflightRepository.countActiveLegacyRewardCutoverConflicts(startDate) == 0L;
    }
}
