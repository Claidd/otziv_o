package com.hunt.otziv.config.settings.service;

import com.hunt.otziv.config.settings.api.ManagerProblemDiagnosticSettings;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManagerProblemDiagnosticSettingsAdapter implements ManagerProblemDiagnosticSettings {
    private final AppSettingService settings;
    @Override public String mode() { return settings.getString("manager-control.diagnostics.mode", "SHADOW"); }
    @Override public Set<Long> pilotManagerIds() {
        return Arrays.stream(settings.getString("manager-control.diagnostics.pilot-manager-ids", "").split(","))
                .map(String::trim).filter(value -> value.matches("[0-9]{1,18}")).map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }
    @Override public int maxRequestsPerHour() { return settings.getInt("manager-control.diagnostics.max-requests-per-hour", 60); }
    @Override public int maxOutputTokens() { return settings.getInt("manager-control.diagnostics.max-output-tokens", 8000); }
    @Override public int timeoutSeconds() { return settings.getInt("manager-control.diagnostics.timeout-seconds", 45); }
    @Override public boolean thinkingEnabled() { return settings.getBoolean("manager-control.diagnostics.thinking-enabled", true); }
    @Override public String evidenceMode() { return settings.getString("manager.report-review.evidence-mode", "SHADOW"); }
    @Override public Set<Long> evidencePilotManagerIds() {
        return Arrays.stream(settings.getString("manager.report-review.evidence-pilot-manager-ids", "").split(","))
                .map(String::trim).filter(value -> value.matches("[0-9]{1,18}")).map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}
