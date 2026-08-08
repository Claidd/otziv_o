package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardRepairState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorCompletionRewardRepairStateRepository
        extends JpaRepository<ContractorCompletionRewardRepairState, Long> {

    long countByAttemptCountGreaterThan(int attempts);

    long countByNextAttemptAtLessThanEqual(LocalDateTime now);

    Optional<ContractorCompletionRewardRepairState> findFirstByAttemptCountGreaterThanOrderByUpdatedAtDesc(
            int attempts
    );

    @Query("SELECT MIN(state.nextAttemptAt) FROM ContractorCompletionRewardRepairState state")
    LocalDateTime findOldestRetryAt();

    @Query("""
        SELECT MIN(state.nextAttemptAt)
        FROM ContractorCompletionRewardRepairState state
        WHERE state.nextAttemptAt <= :now
    """)
    LocalDateTime findOldestDueRetryAt(@Param("now") LocalDateTime now);
}
