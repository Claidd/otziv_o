package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorShadowBackfillClaim;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractorShadowBackfillClaimRepository
        extends JpaRepository<ContractorShadowBackfillClaim, String> {

    @Modifying
    @Query(value = """
        INSERT IGNORE INTO contractor_shadow_backfill_claims (claim_key, queue_type, source_id)
        VALUES (:claimKey, :queueType, :sourceId)
    """, nativeQuery = true)
    int insertIfMissing(@Param("claimKey") String claimKey,
                        @Param("queueType") String queueType,
                        @Param("sourceId") Long sourceId);

    @Modifying
    @Query("""
        UPDATE ContractorShadowBackfillClaim claim
        SET claim.claimToken = :token,
            claim.leaseUntil = :leaseUntil
        WHERE claim.claimKey = :claimKey
          AND claim.completedAt IS NULL
          AND (claim.leaseUntil IS NULL OR claim.leaseUntil < :now)
          AND (claim.nextRetryAt IS NULL OR claim.nextRetryAt <= :now)
    """)
    int claim(@Param("claimKey") String claimKey,
              @Param("token") String token,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT claim FROM ContractorShadowBackfillClaim claim WHERE claim.claimKey = :claimKey")
    Optional<ContractorShadowBackfillClaim> findByClaimKeyForUpdate(@Param("claimKey") String claimKey);

    long countByCompletedAtIsNullAndRetryAttemptsGreaterThan(int retryAttempts);

    long countByCompletedAtIsNullAndLeaseUntilAfter(LocalDateTime now);

    @Query("""
        SELECT COUNT(claim)
        FROM ContractorShadowBackfillClaim claim
        WHERE claim.completedAt IS NULL
          AND claim.claimToken IS NOT NULL
          AND (claim.leaseUntil IS NULL OR claim.leaseUntil <= :now)
    """)
    long countExpiredClaims(@Param("now") LocalDateTime now);

    @Query("""
        SELECT COUNT(claim)
        FROM ContractorShadowBackfillClaim claim
        WHERE claim.completedAt IS NULL
          AND claim.retryAttempts > 0
          AND claim.nextRetryAt IS NOT NULL
          AND claim.nextRetryAt <= :now
    """)
    long countDueRetries(@Param("now") LocalDateTime now);

    @Query("""
        SELECT MIN(claim.nextRetryAt)
        FROM ContractorShadowBackfillClaim claim
        WHERE claim.completedAt IS NULL AND claim.retryAttempts > 0
    """)
    LocalDateTime findOldestRetryAt();

    @Query("""
        SELECT MIN(claim.nextRetryAt)
        FROM ContractorShadowBackfillClaim claim
        WHERE claim.completedAt IS NULL
          AND claim.retryAttempts > 0
          AND claim.nextRetryAt IS NOT NULL
          AND claim.nextRetryAt <= :now
    """)
    LocalDateTime findOldestDueRetryAt(@Param("now") LocalDateTime now);

    Optional<ContractorShadowBackfillClaim>
    findFirstByCompletedAtIsNullAndRetryAttemptsGreaterThanOrderByUpdatedAtDesc(int retryAttempts);
}
