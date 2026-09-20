package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.z_zp.model.Zp;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read-only cutover checks kept separate from the legacy reward repository. */
public interface ContractorCompletionCutoverPreflightRepository extends JpaRepository<Zp, Long> {

    @Query(value = ContractorLegacyRewardConflictSql.ACTIVATION, nativeQuery = true)
    long countActiveLegacyRewardCutoverConflicts(@Param("startDate") LocalDate startDate);

    /** Current paid-task evidence can resolve a runtime warning, never an activation blocker. */
    @Query(value = ContractorLegacyRewardConflictSql.RUNTIME, nativeQuery = true)
    long countActiveLegacyRewardRuntimeConflicts(@Param("startDate") LocalDate startDate);
}
