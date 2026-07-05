package com.hunt.otziv.performers.repository;

import com.hunt.otziv.performers.model.PerformerTaskEvidence;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PerformerTaskEvidenceRepository extends CrudRepository<PerformerTaskEvidence, Long> {
}
