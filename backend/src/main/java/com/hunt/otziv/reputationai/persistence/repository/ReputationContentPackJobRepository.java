package com.hunt.otziv.reputationai.persistence.repository;

import com.hunt.otziv.reputationai.persistence.model.ReputationContentPackJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReputationContentPackJobRepository extends JpaRepository<ReputationContentPackJobEntity, Long> {
    Optional<ReputationContentPackJobEntity> findByCompanyId(Long companyId);

    /** The executor queue lives in memory: after a restart queued/running jobs can never finish. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("""
        UPDATE ReputationContentPackJobEntity job
        SET job.status = com.hunt.otziv.reputationai.persistence.model.ContentPackJobStatus.FAILED,
            job.errorMessage = :message,
            job.completedAt = :now,
            job.updatedAt = :now
        WHERE job.status IN (com.hunt.otziv.reputationai.persistence.model.ContentPackJobStatus.QUEUED,
                             com.hunt.otziv.reputationai.persistence.model.ContentPackJobStatus.RUNNING)
    """)
    int failInterruptedJobs(@org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now,
                            @org.springframework.data.repository.query.Param("message") String message);
}
