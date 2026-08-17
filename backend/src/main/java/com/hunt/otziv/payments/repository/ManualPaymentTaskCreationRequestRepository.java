package com.hunt.otziv.payments.repository;

import com.hunt.otziv.payments.model.ManualPaymentTaskCreationRequest;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ManualPaymentTaskCreationRequestRepository
        extends JpaRepository<ManualPaymentTaskCreationRequest, String> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT IGNORE INTO manual_payment_task_creation_requests
            (operation_key, payload_hash, task_id, created_at, completed_at)
        VALUES (:operationKey, :payloadHash, NULL, CURRENT_TIMESTAMP(6), NULL)
    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("operationKey") String operationKey,
            @Param("payloadHash") String payloadHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT request
        FROM ManualPaymentTaskCreationRequest request
        WHERE request.operationKey = :operationKey
    """)
    Optional<ManualPaymentTaskCreationRequest> findByOperationKeyForUpdate(
            @Param("operationKey") String operationKey
    );
}
