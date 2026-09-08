package com.hunt.otziv.admin.controller;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.worker_activity.account_action.WorkerAccountActionCooldownRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/dictionaries/worker-account-action-settings")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiAdminWorkerAccountActionSettingsController {

    private final AppSettingService appSettingService;

    @GetMapping
    public WorkerAccountActionSettingsResponse getSettings() {
        String value = appSettingService.getStringFresh(
                AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, "60"
        );
        int seconds = 60;
        try {
            int configured = Integer.parseInt(value);
            if (configured >= 0 && configured <= 3600) {
                seconds = configured;
            }
        } catch (NumberFormatException ignored) {
            // A malformed setting retains the same safe default as action admission.
        }
        return new WorkerAccountActionSettingsResponse(currentEnabled(), seconds);
    }

    @PutMapping
    @Transactional
    public WorkerAccountActionSettingsResponse updateSettings(@RequestBody WorkerAccountActionSettingsRequest request) {
        if (request == null || request.cooldownSeconds() == null) {
            throw invalidDuration();
        }
        int seconds;
        try {
            // BigDecimal prevents Jackson from silently truncating fractional JSON numbers.
            seconds = request.cooldownSeconds().intValueExact();
        } catch (ArithmeticException ignored) {
            throw invalidDuration();
        }
        if (seconds < 0 || seconds > 3600) {
            throw invalidDuration();
        }
        boolean enabled = request.enabled() != null ? request.enabled() : currentEnabled();
        int savedSeconds = appSettingService.setInt(
                AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, seconds
        );
        // Old clients send only duration; do not overwrite the separately configured switch.
        if (request.enabled() != null) {
            appSettingService.setBoolean(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_ENABLED, enabled);
        }
        return new WorkerAccountActionSettingsResponse(enabled, savedSeconds);
    }

    private ResponseStatusException invalidDuration() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Длительность паузы должна быть целым числом от 0 до 3600 секунд");
    }

    private boolean currentEnabled() {
        return WorkerAccountActionCooldownRepository.parseEnabled(appSettingService.getStringFresh(
                AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_ENABLED, "true"));
    }

    public record WorkerAccountActionSettingsRequest(Boolean enabled, BigDecimal cooldownSeconds) {
        public WorkerAccountActionSettingsRequest(BigDecimal cooldownSeconds) {
            this(null, cooldownSeconds);
        }
    }

    public record WorkerAccountActionSettingsResponse(boolean enabled, int cooldownSeconds) {}
}
