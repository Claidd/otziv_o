package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorRewardRepairClaim;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorRewardRepairClaimRepository
        extends JpaRepository<ContractorRewardRepairClaim, Long> {

    long countByRetryAttemptsGreaterThan(int attempts);

    long countByLeaseUntilAfter(LocalDateTime now);

    @Query("""
        SELECT COUNT(claim)
        FROM ContractorRewardRepairClaim claim
        WHERE claim.claimToken IS NOT NULL
          AND (claim.leaseUntil IS NULL OR claim.leaseUntil <= :now)
    """)
    long countExpiredClaims(@Param("now") LocalDateTime now);

    @Query("""
        SELECT COUNT(claim)
        FROM ContractorRewardRepairClaim claim
        WHERE claim.retryAttempts > 0
          AND claim.nextRetryAt IS NOT NULL
          AND claim.nextRetryAt <= :now
    """)
    long countDueRetries(@Param("now") LocalDateTime now);

    @Query("""
        SELECT MIN(claim.nextRetryAt)
        FROM ContractorRewardRepairClaim claim
        WHERE claim.retryAttempts > 0
    """)
    LocalDateTime findOldestRetryAt();

    @Query("""
        SELECT MIN(claim.nextRetryAt)
        FROM ContractorRewardRepairClaim claim
        WHERE claim.retryAttempts > 0
          AND claim.nextRetryAt IS NOT NULL
          AND claim.nextRetryAt <= :now
    """)
    LocalDateTime findOldestDueRetryAt(@Param("now") LocalDateTime now);

    Optional<ContractorRewardRepairClaim>
    findFirstByRetryAttemptsGreaterThanOrderByUpdatedAtDesc(int attempts);

    @Modifying
    @Query(value = """
        INSERT IGNORE INTO contractor_reward_repair_claims (source_zp_id)
        VALUES (:sourceZpId)
    """, nativeQuery = true)
    int insertIfMissing(@Param("sourceZpId") Long sourceZpId);

    @Modifying
    @Query("""
        UPDATE ContractorRewardRepairClaim claim
        SET claim.claimToken = :token,
            claim.leaseUntil = :leaseUntil
        WHERE claim.sourceZpId = :sourceZpId
          AND (claim.leaseUntil IS NULL OR claim.leaseUntil < :now)
          AND (claim.nextRetryAt IS NULL OR claim.nextRetryAt <= :now)
    """)
    int claim(@Param("sourceZpId") Long sourceZpId,
              @Param("token") String token,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT claim FROM ContractorRewardRepairClaim claim WHERE claim.sourceZpId = :sourceZpId")
    Optional<ContractorRewardRepairClaim> findBySourceZpIdForUpdate(@Param("sourceZpId") Long sourceZpId);
}
