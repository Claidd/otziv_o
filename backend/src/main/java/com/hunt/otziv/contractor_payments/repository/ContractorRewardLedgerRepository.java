package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorRewardLedgerEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorRewardLedgerRepository extends JpaRepository<ContractorRewardLedgerEntry, Long> {

    /** Current read used only after the owning payment profile was locked. */
    @Query(value = """
        SELECT COALESCE(SUM(entry.amount_kopecks), 0)
        FROM contractor_reward_ledger entry
        WHERE entry.profile_id = :profileId
          AND entry.active = TRUE
        FOR UPDATE
    """, nativeQuery = true)
    long sumActiveForCapacityUpdate(@Param("profileId") Long profileId);

    Optional<ContractorRewardLedgerEntry> findBySourceZpIdAndProfileIdAndAttributionKey(
            Long sourceZpId,
            Long profileId,
            long attributionKey
    );

    List<ContractorRewardLedgerEntry> findAllBySourceZpIdAndProfileId(Long sourceZpId, Long profileId);

    List<ContractorRewardLedgerEntry> findAllBySourceZpId(Long sourceZpId);

    @Query("""
        SELECT COALESCE(SUM(e.amountKopecks), 0)
        FROM ContractorRewardLedgerEntry e
        WHERE e.profile.id = :profileId AND e.active = true
    """)
    long sumActiveByProfileId(@Param("profileId") Long profileId);

    @Query("""
        SELECT COALESCE(SUM(e.amountKopecks), 0)
        FROM ContractorRewardLedgerEntry e
        WHERE e.profile.id = :profileId
          AND e.active = true
          AND e.occurredOn >= :from
          AND e.occurredOn < :to
    """)
    long sumActiveByProfileIdAndPeriod(@Param("profileId") Long profileId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    List<ContractorRewardLedgerEntry> findAllByProfileIdAndActiveTrueOrderByOccurredOnAscIdAsc(Long profileId);
}
