package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardMarker;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractorCompletionRewardMarkerRepository
        extends JpaRepository<ContractorCompletionRewardMarker, Long> {

    Optional<ContractorCompletionRewardMarker> findByOrderIdAndLogicalSource(
            Long orderId,
            String logicalSource
    );

    boolean existsByOrderId(Long orderId);
}
