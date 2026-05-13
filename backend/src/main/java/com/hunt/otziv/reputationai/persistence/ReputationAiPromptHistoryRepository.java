package com.hunt.otziv.reputationai.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReputationAiPromptHistoryRepository extends JpaRepository<ReputationAiPromptHistoryEntity, Long> {
    List<ReputationAiPromptHistoryEntity> findByPromptKeyOrderByCreatedAtDesc(String promptKey);
}
