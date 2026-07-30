package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadTransferExecutionResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadTransferWorkflowResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadEmergencyAssignmentResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferLiveMonitorRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadTransferLiveMonitorService {

    private static final int MAX_ROWS = 200;

    private final WorkloadTransferLiveMonitorRepository repository;

    @Transactional(readOnly = true)
    public List<WorkloadTransferWorkflowResponse> workflows(Long managerId) {
        return repository.findWorkflows(validManager(managerId), MAX_ROWS).stream()
                .map(value -> new WorkloadTransferWorkflowResponse(
                        number(value.getWorkflowId()),
                        value.getWorkflowKey(),
                        value.getMode(),
                        value.getStatus(),
                        number(value.getManagerId()),
                        value.getManagerName(),
                        number(value.getSourceWorkerId()),
                        value.getSourceWorkerName(),
                        value.getTargetWorkerId(),
                        value.getTargetWorkerName(),
                        number(value.getCompanyId()),
                        value.getCompanyTitle(),
                        integer(value.getFailureNumber()),
                        integer(value.getTransferPercent()),
                        number(value.getProblemUnits()),
                        number(value.getEstimatedMinutes()),
                        number(value.getActiveOrderCount()),
                        number(value.getNewUnitCount()),
                        number(value.getCorrectionCount()),
                        number(value.getNagulCount()),
                        number(value.getPublishCount()),
                        number(value.getRecoveryCount()),
                        number(value.getBadCount()),
                        Boolean.TRUE.equals(value.getOwnerConfirmationRequired()),
                        value.getOwnerConfirmedAt(),
                        value.getLastErrorCode(),
                        value.getLastErrorMessage(),
                        value.getDecisionDate(),
                        value.getLastTransitionAt(),
                        value.getCreatedAt(),
                        value.getCurrentOfferExpiresAt(),
                        number(value.getCandidateCount()),
                        number(value.getDeclinedCandidateCount()),
                        number(value.getUnavailableCandidateCount())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkloadTransferExecutionResponse> executions(Long managerId) {
        return repository.findExecutions(validManager(managerId), MAX_ROWS).stream()
                .map(value -> new WorkloadTransferExecutionResponse(
                        number(value.getExecutionId()),
                        number(value.getWorkflowId()),
                        value.getStatus(),
                        number(value.getManagerId()),
                        value.getManagerName(),
                        number(value.getSourceWorkerId()),
                        value.getSourceWorkerName(),
                        number(value.getTargetWorkerId()),
                        value.getTargetWorkerName(),
                        number(value.getCompanyId()),
                        value.getCompanyTitle(),
                        integer(value.getOrderCount()),
                        integer(value.getReviewCount()),
                        integer(value.getBadTaskCount()),
                        integer(value.getRecoveryTaskCount()),
                        value.getStartedAt(),
                        value.getAppliedAt(),
                        value.getRollbackDeadlineAt(),
                        value.getRolledBackAt(),
                        value.getErrorCode(),
                        value.getErrorMessage()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkloadEmergencyAssignmentResponse> emergencyAssignments(
            Long managerId
    ) {
        return repository.findEmergencyAssignments(
                validManager(managerId),
                MAX_ROWS
        ).stream().map(value -> new WorkloadEmergencyAssignmentResponse(
                number(value.getAssignmentId()),
                value.getMode(),
                value.getStatus(),
                number(value.getSourceManagerId()),
                value.getSourceManagerName(),
                number(value.getSourceWorkerId()),
                value.getSourceWorkerName(),
                number(value.getTargetManagerId()),
                value.getTargetManagerName(),
                number(value.getTargetWorkerId()),
                value.getTargetWorkerName(),
                number(value.getCompanyId()),
                value.getCompanyTitle(),
                number(value.getReviewId()),
                value.getReason(),
                value.getTargetNotificationStatus(),
                value.getAuditNotificationStatus(),
                integer(value.getNotificationAttempts()),
                value.getDecisionDate(),
                value.getAppliedAt(),
                value.getRollbackDeadlineAt(),
                value.getRolledBackAt(),
                value.getLastError()
        )).toList();
    }

    private Long validManager(Long managerId) {
        return managerId != null && managerId > 0 ? managerId : null;
    }

    private long number(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private int integer(Number value) {
        return value == null ? 0 : value.intValue();
    }
}
