package com.hunt.otziv.reputationai.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReputationContentPackJobRepository extends JpaRepository<ReputationContentPackJobEntity, Long> {
    Optional<ReputationContentPackJobEntity> findByCompanyId(Long companyId);
}
