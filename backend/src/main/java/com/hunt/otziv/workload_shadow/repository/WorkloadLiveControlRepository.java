package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.config.settings.model.AppSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface WorkloadLiveControlRepository extends Repository<AppSetting, String> {

    /** Locks the live revision row until the caller's transaction commits. */
    @Query(value = """
            SELECT CAST(TRIM(revision_setting.setting_value) AS UNSIGNED)
                       AS settingsRevision,
                   UPPER(TRIM(mode_setting.setting_value)) AS mode,
                   LOWER(TRIM(apply_setting.setting_value)) AS applyEnabled
            FROM app_settings revision_setting
            JOIN app_settings mode_setting
              ON mode_setting.setting_key = 'workload.live.mode'
            JOIN app_settings apply_setting
              ON apply_setting.setting_key = 'workload.live.apply-enabled'
            WHERE revision_setting.setting_key = 'workload.live.settings-revision'
            FOR UPDATE
            """, nativeQuery = true)
    Optional<LiveControlProjection> lockState();

    interface LiveControlProjection {
        Long getSettingsRevision();

        String getMode();

        String getApplyEnabled();
    }
}
