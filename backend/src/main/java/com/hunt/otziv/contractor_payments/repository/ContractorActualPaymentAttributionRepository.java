package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorActualPaymentAttributionRepository
        extends JpaRepository<ContractorActualPaymentAttribution, Long> {

    Optional<ContractorActualPaymentAttribution> findByAttributionKey(String attributionKey);

    @Query("SELECT COUNT(a)>0 FROM ContractorActualPaymentAttribution a WHERE a.corrected=false AND a.sourceKind=:sourceKind AND a.sourceId=:sourceId AND (a.evidenceId=:evidenceId OR (:evidenceId IS NULL AND a.evidenceId IS NULL))")
    boolean existsBySourceKindAndSourceIdAndEvidenceId(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId,
            Long evidenceId
    );

    @Query("""
        SELECT DISTINCT a.sourceId FROM ContractorActualPaymentAttribution a
        WHERE a.corrected=false AND a.sourceKind = :kind AND a.sourceId IN :ids AND a.evidenceId IS NULL
        """)
    List<Long> findSourcesWithLegacyAttribution(@Param("kind") ContractorActualPaymentSourceKind kind,
                                               @Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ContractorActualPaymentAttribution a WHERE a.attributionKey = :key")
    Optional<ContractorActualPaymentAttribution> findByAttributionKeyForUpdate(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ContractorActualPaymentAttribution a WHERE a.id = :id")
    Optional<ContractorActualPaymentAttribution> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT a FROM ContractorActualPaymentAttribution a WHERE a.corrected=false AND a.sourceKind=:sourceKind AND a.sourceId=:sourceId ORDER BY a.effectiveAt,a.id")
    List<ContractorActualPaymentAttribution> findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a
        FROM ContractorActualPaymentAttribution a
        WHERE a.corrected=false AND a.sourceKind = :sourceKind
          AND a.sourceId = :sourceId
        ORDER BY a.effectiveAt, a.id
    """)
    List<ContractorActualPaymentAttribution> findAllBySourceForUpdate(
            @Param("sourceKind") ContractorActualPaymentSourceKind sourceKind,
            @Param("sourceId") Long sourceId
    );

    @Query("SELECT a FROM ContractorActualPaymentAttribution a WHERE a.corrected=false AND a.effectiveAt>=:from AND a.effectiveAt<:to ORDER BY a.effectiveAt,a.id")
    List<ContractorActualPaymentAttribution>
            findAllByEffectiveAtGreaterThanEqualAndEffectiveAtLessThanOrderByEffectiveAtAscIdAsc(
                    LocalDateTime from,
                    LocalDateTime to
            );


    @Query("""
        SELECT a.actualRecipientProfileId AS profileId,
               COUNT(a.id) AS transferCount,
               COALESCE(SUM(a.amountKopecks), 0) AS transferAmountKopecks
        FROM ContractorActualPaymentAttribution a
        WHERE a.corrected=false AND a.accountingMode = :mode
          AND a.actualRecipientProfileId IN :profileIds
          AND a.effectiveAt >= :from
          AND a.effectiveAt < :to
        GROUP BY a.actualRecipientProfileId
    """)
    List<ProfileActualTransferSummary> summarizeProfileActualTransfersInPeriod(
            @Param("profileIds") Collection<Long> profileIds,
            @Param("mode") ContractorAllocationMode mode,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    interface ProfileActualTransferSummary {
        Long getProfileId();

        Long getTransferCount();

        Long getTransferAmountKopecks();
    }


}
