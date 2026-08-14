package com.hunt.otziv.workload_shadow.repository;

import com.hunt.otziv.config.settings.model.AppSetting;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkloadShadowSettingsRepository extends Repository<AppSetting, String> {

    @Query(value = """
            SELECT setting_key AS settingKey,
                   setting_value AS settingValue
            FROM app_settings
            WHERE setting_key LIKE CONCAT(:prefix, '%')
            ORDER BY setting_key
            """, nativeQuery = true)
    List<SettingProjection> findAllByPrefix(@Param("prefix") String prefix);


    @Query(value = """
            SELECT COUNT(*)
            FROM app_settings mode_setting
            JOIN app_settings apply_setting
              ON apply_setting.setting_key = 'workload.live.apply-enabled'
            WHERE mode_setting.setting_key = 'workload.live.mode'
              AND UPPER(TRIM(mode_setting.setting_value)) IN ('CANARY', 'LIVE')
              AND LOWER(TRIM(apply_setting.setting_value)) = 'true'
            """, nativeQuery = true)
    long countActiveLiveMode();
    @Modifying
    @Query(value = """
            UPDATE app_settings target_setting
            JOIN JSON_TABLE(
                :settingsJson,
                '$[*]' COLUMNS (
                    setting_key VARCHAR(100)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                        PATH '$.settingKey',
                    setting_value VARCHAR(500)
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                        PATH '$.settingValue'
                )
            ) requested_setting
              ON requested_setting.setting_key = target_setting.setting_key
            JOIN app_settings current_revision
              ON current_revision.setting_key = :revisionKey
             AND CAST(TRIM(current_revision.setting_value) AS UNSIGNED) = :expectedRevision
            SET target_setting.setting_value = requested_setting.setting_value,
                target_setting.updated_at = TIMESTAMPADD(
                    MICROSECOND,
                    1,
                    GREATEST(target_setting.updated_at, CURRENT_TIMESTAMP(6))
                )
            WHERE requested_setting.setting_key LIKE CONCAT(:prefix, '%')
              AND target_setting.setting_key LIKE CONCAT(:prefix, '%')
            """, nativeQuery = true)
    int updateAllWithRevision(
            @Param("settingsJson") String settingsJson,
            @Param("prefix") String prefix,
            @Param("revisionKey") String revisionKey,
            @Param("expectedRevision") long expectedRevision
    );

    interface SettingProjection {

        String getSettingKey();

        String getSettingValue();
    }
}
