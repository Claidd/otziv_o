package com.hunt.otziv.config.settings.repository;

import com.hunt.otziv.config.settings.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
