package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorRewardSyncMarker;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractorRewardSyncMarkerRepository extends JpaRepository<ContractorRewardSyncMarker, Long> {

    Optional<ContractorRewardSyncMarker> findBySourceZpId(Long sourceZpId);
}
