package com.hunt.otziv.reputationai.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReputationDeepReportJobRepository extends JpaRepository<ReputationDeepReportJobEntity, Long> {
    Optional<ReputationDeepReportJobEntity> findByCompanyId(Long companyId);
}
