package com.hunt.otziv.worker_activity.repository;

import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparation;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkerCredentialPreparationRepository extends JpaRepository<WorkerCredentialPreparation, Long> {

    Optional<WorkerCredentialPreparation> findByWorkerUserIdAndScope(Long workerUserId, WorkerCredentialPreparationScope scope);

    @Modifying
    long deleteByWorkerUserIdAndScope(Long workerUserId, WorkerCredentialPreparationScope scope);

    @Modifying
    long deleteByUpdatedAtBefore(LocalDateTime cutoff);
}
