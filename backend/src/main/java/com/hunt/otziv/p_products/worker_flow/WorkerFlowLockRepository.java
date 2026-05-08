package com.hunt.otziv.p_products.worker_flow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkerFlowLockRepository extends JpaRepository<WorkerFlowLock, String> {

    @Modifying
    @Query("DELETE FROM WorkerFlowLock flowLock WHERE flowLock.lockKey = :lockKey")
    int deleteByLockKey(@Param("lockKey") String lockKey);
}
