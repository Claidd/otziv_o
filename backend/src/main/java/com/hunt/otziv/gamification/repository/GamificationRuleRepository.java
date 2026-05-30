package com.hunt.otziv.gamification.repository;

import com.hunt.otziv.gamification.model.GamificationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GamificationRuleRepository extends JpaRepository<GamificationRule, String> {
}
