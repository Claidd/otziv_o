package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorCompletionCutoverState;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractorCompletionCutoverStateRepository
        extends JpaRepository<ContractorCompletionCutoverState, Long> {

    /** INSERT IGNORE makes concurrent first activation deterministic and non-destructive. */
    @Modifying
    @Query(value = """
        INSERT IGNORE INTO contractor_completion_cutover_state (
            id, attribution_start_date, locked_at
        ) VALUES (1, :startDate, :lockedAt)
    """, nativeQuery = true)
    int insertSingletonIfAbsent(
            @Param("startDate") LocalDate startDate,
            @Param("lockedAt") LocalDateTime lockedAt
    );
}
