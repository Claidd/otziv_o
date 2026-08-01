package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.config.settings.service.AppSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReputationAiRuntimeSwitch {

    private final AppSettingService appSettingService;

    public boolean isEnabled() {
        try {
            return appSettingService.getBooleanFreshFailClosed(
                    AppSettingService.REPUTATION_AI_ENABLED,
                    true
            );
        } catch (RuntimeException exception) {
            // A database/settings failure must not accidentally authorize an
            // outbound provider call. Keep logs free of connection messages or
            // setting values, which may contain infrastructure details.
            log.error(
                    "Reputation AI runtime switch read failed closed: failureType={}",
                    exception.getClass().getSimpleName()
            );
            return false;
        }
    }

    public boolean setEnabled(boolean enabled) {
        return appSettingService.setBoolean(AppSettingService.REPUTATION_AI_ENABLED, enabled);
    }
}
