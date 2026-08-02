package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The deployment property is a hard master switch. The database value is the
 * cross-node operational switch and is always read fresh; a missing row keeps
 * the compatible enabled default while malformed data fails closed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalReviewCheckRuntimeSwitch {

    private final ExternalReviewCheckProperties properties;
    private final AppSettingService appSettingService;

    public boolean isEnabled() {
        return properties.isEnabled() && operatorEnabledFresh();
    }

    public Status status() {
        boolean hardEnabled = properties.isEnabled();
        boolean operatorEnabled = operatorEnabledFresh();
        return new Status(hardEnabled && operatorEnabled, hardEnabled, operatorEnabled);
    }

    private boolean operatorEnabledFresh() {
        try {
            return appSettingService.getBooleanFreshFailClosed(
                    AppSettingService.EXTERNAL_REVIEW_CHECK_ENABLED,
                    true
            );
        } catch (RuntimeException exception) {
            // A safety switch whose state cannot be read must never permit an
            // outbound worker call. Do not log database messages or values.
            log.error(
                    "External review runtime switch read failed closed: failureType={}",
                    exception.getClass().getSimpleName()
            );
            return false;
        }
    }

    public Status setOperatorEnabled(boolean enabled) {
        appSettingService.setBoolean(AppSettingService.EXTERNAL_REVIEW_CHECK_ENABLED, enabled);
        return status();
    }

    public record Status(boolean enabled, boolean hardEnabled, boolean operatorEnabled) {
    }
}
