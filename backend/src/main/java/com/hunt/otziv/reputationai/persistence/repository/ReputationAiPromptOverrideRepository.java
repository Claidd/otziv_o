package com.hunt.otziv.reputationai.persistence.repository;

import com.hunt.otziv.reputationai.persistence.model.ReputationAiPromptOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReputationAiPromptOverrideRepository extends JpaRepository<ReputationAiPromptOverrideEntity, String> {
}
