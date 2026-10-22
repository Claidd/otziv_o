package com.hunt.otziv.config.settings.api;

import java.util.Set;

/** Read-only rollout and budget settings; conveys no authority to modify manager cards or source data. */
public interface ManagerProblemDiagnosticSettings {
    String mode();
    Set<Long> pilotManagerIds();
    int maxRequestsPerHour();
    int maxOutputTokens();
    int timeoutSeconds();
    boolean thinkingEnabled();
    String evidenceMode();
    Set<Long> evidencePilotManagerIds();
}
