package com.hunt.otziv.config.settings.repository;

import com.hunt.otziv.config.settings.model.AppSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {

    /**
     * Scalar native read used by safety switches. Returning the value rather
     * than the managed entity bypasses an already-populated persistence context
     * and therefore observes a change committed by another application node.
     */
    @Query(value = """
            SELECT setting_value
            FROM app_settings
            WHERE setting_key = :key
            """, nativeQuery = true)
    Optional<String> findFreshValueByKey(@Param("key") String key);
}
