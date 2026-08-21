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

    boolean existsByEvidenceId(Long evidenceId);

    boolean existsBySourceKindAndSourceIdAndEvidenceId(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId,
            Long evidenceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ContractorActualPaymentAttribution a WHERE a.attributionKey = :key")
    Optional<ContractorActualPaymentAttribution> findByAttributionKeyForUpdate(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ContractorActualPaymentAttribution a WHERE a.id = :id")
    Optional<ContractorActualPaymentAttribution> findByIdForUpdate(@Param("id") Long id);

    List<ContractorActualPaymentAttribution> findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a
        FROM ContractorActualPaymentAttribution a
        WHERE a.sourceKind = :sourceKind
          AND a.sourceId = :sourceId
        ORDER BY a.effectiveAt, a.id
    """)
    List<ContractorActualPaymentAttribution> findAllBySourceForUpdate(
            @Param("sourceKind") ContractorActualPaymentSourceKind sourceKind,
            @Param("sourceId") Long sourceId
    );

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
        WHERE a.accountingMode = :mode
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
