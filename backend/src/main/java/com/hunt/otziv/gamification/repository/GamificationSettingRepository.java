package com.hunt.otziv.gamification.repository;

import com.hunt.otziv.gamification.model.GamificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GamificationSettingRepository extends JpaRepository<GamificationSetting, String> {
}
