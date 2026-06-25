package com.hunt.otziv.worker_activity.repository;

import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkerActivityEventRepository extends JpaRepository<WorkerActivityEvent, Long> {

    long countByWorkerUserIdAndActionInAndCreatedAtGreaterThanEqual(
            Long workerUserId,
            Collection<WorkerActivityAction> actions,
            LocalDateTime since
    );

    long countByWorkerUserIdAndActionInAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
            Long workerUserId,
            Collection<WorkerActivityAction> actions,
            String entityType,
            Long entityId,
            LocalDateTime since
    );

    boolean existsByWorkerUserIdAndActionInAndReviewIdAndCreatedAtGreaterThanEqual(
            Long workerUserId,
            Collection<WorkerActivityAction> actions,
            Long reviewId,
            LocalDateTime since
    );

    boolean existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtGreaterThanEqualAndDetailsContaining(
            Long workerUserId,
            WorkerActivityAction action,
            Long reviewId,
            LocalDateTime since,
            String botToken
    );

    boolean existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtBetweenAndDetailsContaining(
            Long workerUserId,
            WorkerActivityAction action,
            Long reviewId,
            LocalDateTime since,
            LocalDateTime until,
            String botToken
    );

    boolean existsByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetween(
            Long workerUserId,
            Collection<WorkerActivityAction> actions,
            Long reviewId,
            LocalDateTime since,
            LocalDateTime until
    );

    Optional<WorkerActivityEvent> findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long workerUserId,
            Collection<WorkerActivityAction> actions,
            Long reviewId,
            LocalDateTime since,
            LocalDateTime until
    );

    Optional<WorkerActivityEvent> findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenAndDetailsContainingOrderByCreatedAtDesc(
            Long workerUserId,
            Collection<WorkerActivityAction> actions,
            Long reviewId,
            LocalDateTime since,
            LocalDateTime until,
            String botToken
    );

    List<WorkerActivityEvent> findTop50ByWorkerUserIdAndActionInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Long workerUserId,
            Collection<WorkerActivityAction> actions,
            LocalDateTime since
    );

    @Modifying
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
