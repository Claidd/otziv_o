package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.workload_shadow.repository.entity.WorkloadShadowRecalculationLockEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface WorkloadShadowRecalculationLockRepository
        extends Repository<WorkloadShadowRecalculationLockEntity, String> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO workload_shadow_recalculation_locks (
                lock_name,
                lease_until
            ) VALUES (
                :lockName,
                '1970-01-01 00:00:00.000000'
            )
            """, nativeQuery = true)
    int ensureLockRow(@Param("lockName") String lockName);

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_recalculation_locks
            SET takeover_count = takeover_count + CASE
                    WHEN owner_token IS NOT NULL
                     AND lease_until <= CURRENT_TIMESTAMP(6)
                    THEN 1
                    ELSE 0
                END,
                last_recovered_at = CASE
                    WHEN owner_token IS NOT NULL
                     AND lease_until <= CURRENT_TIMESTAMP(6)
                    THEN CURRENT_TIMESTAMP(6)
                    ELSE last_recovered_at
                END,
                owner_instance_id = :instanceId,
                owner_token = :ownerToken,
                run_id = NULL,
                acquired_at = CURRENT_TIMESTAMP(6),
                renewed_at = CURRENT_TIMESTAMP(6),
                lease_until = TIMESTAMPADD(
                    SECOND,
                    :leaseSeconds,
                    CURRENT_TIMESTAMP(6)
                )
            WHERE lock_name = :lockName
              AND (
                  owner_token IS NULL
                  OR lease_until <= CURRENT_TIMESTAMP(6)
              )
            """, nativeQuery = true)
    int tryAcquire(
            @Param("lockName") String lockName,
            @Param("instanceId") String instanceId,
            @Param("ownerToken") String ownerToken,
            @Param("leaseSeconds") int leaseSeconds
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_recalculation_locks
            SET run_id = :runId,
                renewed_at = CURRENT_TIMESTAMP(6),
                lease_until = TIMESTAMPADD(
                    SECOND,
                    :leaseSeconds,
                    CURRENT_TIMESTAMP(6)
                )
            WHERE lock_name = :lockName
              AND owner_instance_id = :instanceId
              AND owner_token = :ownerToken
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, nativeQuery = true)
    int attachRun(
            @Param("lockName") String lockName,
            @Param("instanceId") String instanceId,
            @Param("ownerToken") String ownerToken,
            @Param("runId") long runId,
            @Param("leaseSeconds") int leaseSeconds
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_recalculation_locks
            SET renewed_at = CURRENT_TIMESTAMP(6),
                lease_until = TIMESTAMPADD(
                    SECOND,
                    :leaseSeconds,
                    CURRENT_TIMESTAMP(6)
                )
            WHERE lock_name = :lockName
              AND owner_instance_id = :instanceId
              AND owner_token = :ownerToken
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, nativeQuery = true)
    int renew(
            @Param("lockName") String lockName,
            @Param("instanceId") String instanceId,
            @Param("ownerToken") String ownerToken,
            @Param("leaseSeconds") int leaseSeconds
    );

    @Modifying
    @Query(value = """
            UPDATE workload_shadow_recalculation_locks
            SET owner_instance_id = NULL,
                owner_token = NULL,
                run_id = NULL,
                acquired_at = NULL,
                renewed_at = NULL,
                lease_until = CURRENT_TIMESTAMP(6),
                last_released_at = CURRENT_TIMESTAMP(6)
            WHERE lock_name = :lockName
              AND owner_instance_id = :instanceId
              AND owner_token = :ownerToken
            """, nativeQuery = true)
    int release(
            @Param("lockName") String lockName,
            @Param("instanceId") String instanceId,
            @Param("ownerToken") String ownerToken
    );
}
