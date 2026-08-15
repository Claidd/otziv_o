package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import jakarta.persistence.LockModeType;
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


}
