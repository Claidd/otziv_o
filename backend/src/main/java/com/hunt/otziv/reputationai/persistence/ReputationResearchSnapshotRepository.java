package com.hunt.otziv.reputationai.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface ReputationResearchSnapshotRepository extends CrudRepository<ReputationResearchSnapshotEntity, Long> {

    Optional<ReputationResearchSnapshotEntity> findFirstByCompanyIdOrderByCreatedAtDesc(Long companyId);
}
