package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkerRiskDecisionPolicy {

    public void requireAllowed(
            WorkerRiskIncident incident,
            WorkerRiskResolutionAction action,
            String comment
    ) {
        if (!isFinalAction(action)) {
            return;
        }
        if (incident != null && incident.getExplanationQuality() == WorkerRiskExplanationQuality.LOGICAL) {
            return;
        }
        if (clean(comment).length() < 10) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Для решения без подтверждённого содержательного ответа укажите конкретные проверенные факты"
            );
        }
    }

    public void applyDecisionEvidence(
            WorkerRiskIncident incident,
            WorkerRiskResolutionAction action,
            String comment
    ) {
        if (incident == null) {
            return;
        }
        if (!isFinalAction(action)) {
            incident.setManagerResolutionComment(null);
            incident.setDecisionQuality(null);
            incident.setDecisionQualityReason(null);
            incident.setAuditRequired(false);
            return;
        }
        String explanation = clean(comment);
        incident.setManagerResolutionComment(explanation);
        if (incident.getExplanationQuality() == WorkerRiskExplanationQuality.LOGICAL) {
            incident.setDecisionQuality("SUPPORTED");
            incident.setDecisionQualityReason("Решение принято после содержательного ответа специалиста");
            incident.setAuditRequired(false);
            return;
        }
        incident.setDecisionQuality("MANAGER_JUSTIFIED");
        incident.setDecisionQualityReason(explanation);
        incident.setAuditRequired(true);
    }

    public boolean isFinalAction(WorkerRiskResolutionAction action) {
        return action != null
                && action != WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                && action != WorkerRiskResolutionAction.WORKER_WARNED;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
