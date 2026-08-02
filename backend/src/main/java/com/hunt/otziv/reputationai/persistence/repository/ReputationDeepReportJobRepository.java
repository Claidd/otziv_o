package com.hunt.otziv.reputationai.persistence.repository;

import com.hunt.otziv.reputationai.persistence.model.DeepReportJobStatus;
import com.hunt.otziv.reputationai.persistence.model.ReputationDeepReportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReputationDeepReportJobRepository extends JpaRepository<ReputationDeepReportJobEntity, Long> {
    Optional<ReputationDeepReportJobEntity> findFirstByCompanyIdOrderByCreatedAtDesc(Long companyId);

    /**
     * Report payloads contain large JSON/Markdown values. Keep the legacy
     * service-facing method, but cap its backing query so a company with a
     * long history cannot be materialized completely in one request.
     */
    default List<ReputationDeepReportJobEntity> findByCompanyIdOrderByCreatedAtDesc(Long companyId) {
        return findTop20ByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    List<ReputationDeepReportJobEntity> findTop20ByCompanyIdOrderByCreatedAtDesc(Long companyId);

    default List<ReputationDeepReportJobEntity> findByCompanyIdAndStatusInOrderByCreatedAtDesc(
            Long companyId,
            Collection<DeepReportJobStatus> statuses
    ) {
        return findTop20ByCompanyIdAndStatusInOrderByCreatedAtDesc(companyId, statuses);
    }

    List<ReputationDeepReportJobEntity> findTop20ByCompanyIdAndStatusInOrderByCreatedAtDesc(
            Long companyId,
            Collection<DeepReportJobStatus> statuses
    );
}
