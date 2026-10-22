package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.api.ManagerProblemDiagnosticSettings;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Shadow evaluations are saved for review and cannot restrict working access. */
@Service
@RequiredArgsConstructor
public class ManagerReportReviewRolloutPolicy {
    private final ManagerProblemDiagnosticSettings settings;

    public boolean isShadowFor(ManagerReportReviewSession review) {
        if (review.isTestMode()) return false;
        String mode = settings.evidenceMode();
        if ("ENFORCE".equalsIgnoreCase(mode)) return false;
        if (!"PILOT".equalsIgnoreCase(mode) || review.managerId() == null) return true;
        return !settings.evidencePilotManagerIds().contains(review.managerId());
    }
}
