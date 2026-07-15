package com.hunt.otziv.reputationai.persistence.repository;

import com.hunt.otziv.reputationai.persistence.model.DeepReportJobStatus;
import com.hunt.otziv.reputationai.persistence.model.ReputationDeepReportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReputationDeepReportJobRepository extends JpaRepository<ReputationDeepReportJobEntity, Long> {
    Optional<ReputationDeepReportJobEntity> findFirstByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<ReputationDeepReportJobEntity> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<ReputationDeepReportJobEntity> findByCompanyIdAndStatusInOrderByCreatedAtDesc(
            Long companyId,
            Collection<DeepReportJobStatus> statuses
    );
}
