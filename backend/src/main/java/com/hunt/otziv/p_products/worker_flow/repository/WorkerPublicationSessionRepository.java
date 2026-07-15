package com.hunt.otziv.p_products.worker_flow.repository;

import com.hunt.otziv.p_products.worker_flow.model.WorkerPublicationSession;
import com.hunt.otziv.p_products.worker_flow.model.WorkerPublicationSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkerPublicationSessionRepository extends JpaRepository<WorkerPublicationSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT session FROM WorkerPublicationSession session WHERE session.workerId = :workerId")
    Optional<WorkerPublicationSession> findByWorkerIdForUpdate(@Param("workerId") Long workerId);

    List<WorkerPublicationSession> findByStatus(WorkerPublicationSessionStatus status);
}
