package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorDirectSettlement;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorDirectSettlementRepository
        extends JpaRepository<ContractorDirectSettlement, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT settlement
        FROM ContractorDirectSettlement settlement
        WHERE settlement.profile.id = :profileId
          AND settlement.idempotencyKey = :idempotencyKey
    """)
    Optional<ContractorDirectSettlement> findByProfileIdAndIdempotencyKeyForUpdate(
            @Param("profileId") Long profileId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT settlement FROM ContractorDirectSettlement settlement WHERE settlement.id = :id")
    Optional<ContractorDirectSettlement> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT settlement
        FROM ContractorDirectSettlement settlement
        WHERE settlement.originalSettlement.id = :originalSettlementId
        ORDER BY settlement.id
    """)
    List<ContractorDirectSettlement> findAllReversalsForUpdate(
            @Param("originalSettlementId") Long originalSettlementId
    );

    List<ContractorDirectSettlement> findAllByProfileIdOrderByEffectiveAtDescIdDesc(Long profileId);
}
