package com.hunt.otziv.review_recovery.repository;

import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.u_users.model.Manager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface ReviewRecoveryBatchRepository extends JpaRepository<ReviewRecoveryBatch, Long> {

    Optional<ReviewRecoveryBatch> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
            Long orderId,
            Collection<ReviewRecoveryBatchStatus> statuses
    );

    Optional<ReviewRecoveryBatch> findFirstByOrderIdAndStatusAndHoldReleasedAtIsNullOrderByCreatedAtDesc(
            Long orderId,
            ReviewRecoveryBatchStatus status
    );

    boolean existsByIdAndOrderId(Long id, Long orderId);

    long countByStatus(ReviewRecoveryBatchStatus status);

    long countByStatusAndManager(ReviewRecoveryBatchStatus status, Manager manager);

    long countByStatusAndManagerIn(ReviewRecoveryBatchStatus status, Collection<Manager> managers);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ReviewRecoveryBatch batch
        SET batch.status = :archivedStatus,
            batch.archivedAt = :archivedAt,
            batch.updatedAt = :archivedAt
        WHERE batch.status = :clientNotifiedStatus
          AND batch.clientNotifiedAt IS NOT NULL
          AND batch.clientNotifiedAt <= :cutoff
    """)
    int archiveClientNotifiedBatches(
            @Param("clientNotifiedStatus") ReviewRecoveryBatchStatus clientNotifiedStatus,
            @Param("archivedStatus") ReviewRecoveryBatchStatus archivedStatus,
            @Param("cutoff") Instant cutoff,
            @Param("archivedAt") Instant archivedAt
    );
}
