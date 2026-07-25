package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRiskDecisionPolicyTest {

    private final WorkerRiskDecisionPolicy policy = new WorkerRiskDecisionPolicy();

    @Test
    void finalDecisionWithoutAssessedExplanationRequiresConcreteEvidence() {
        WorkerRiskIncident incident = new WorkerRiskIncident();

        assertThrows(
                ResponseStatusException.class,
                () -> policy.requireAllowed(incident, WorkerRiskResolutionAction.VERIFIED, "")
        );
        assertThrows(
                ResponseStatusException.class,
                () -> policy.requireAllowed(incident, WorkerRiskResolutionAction.FALSE_POSITIVE, "проверил")
        );
    }

    @Test
    void logicalExplanationAllowsFinalDecisionWithoutExtraComment() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setExplanationQuality(WorkerRiskExplanationQuality.LOGICAL);

        policy.requireAllowed(incident, WorkerRiskResolutionAction.VERIFIED, "");
        policy.applyDecisionEvidence(incident, WorkerRiskResolutionAction.VERIFIED, "");

        assertEquals("SUPPORTED", incident.getDecisionQuality());
        assertFalse(incident.isAuditRequired());
    }

    @Test
    void managerEvidenceAllowsDecisionButQueuesOwnerAudit() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setExplanationQuality(WorkerRiskExplanationQuality.PARTIAL);
        String evidence = "Проверены заказ, переписка и фактический статус отзыва";

        policy.requireAllowed(incident, WorkerRiskResolutionAction.VIOLATION_CONFIRMED, evidence);
        policy.applyDecisionEvidence(incident, WorkerRiskResolutionAction.VIOLATION_CONFIRMED, evidence);

        assertEquals("MANAGER_JUSTIFIED", incident.getDecisionQuality());
        assertEquals(evidence, incident.getDecisionQualityReason());
        assertTrue(incident.isAuditRequired());
    }

    @Test
    void explanationRequestNeverNeedsDecisionEvidence() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setAuditRequired(true);
        incident.setDecisionQuality("MANAGER_JUSTIFIED");

        policy.requireAllowed(incident, WorkerRiskResolutionAction.EXPLANATION_REQUESTED, "");
        policy.applyDecisionEvidence(incident, WorkerRiskResolutionAction.EXPLANATION_REQUESTED, "");

        assertFalse(incident.isAuditRequired());
        assertEquals(null, incident.getDecisionQuality());
    }
}
