package com.hunt.otziv.workload_shadow.controller;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowEventResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveActivationRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveReadinessResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveStopRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadTransferActionResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadTransferOwnerConfirmationRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadTransferRollbackRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadTransferWorkflowResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadTransferExecutionResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadEmergencyAssignmentResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowRunResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSummaryResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowTransferCaseResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowWorkerResponse;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowCoordinator;
import com.hunt.otziv.workload_shadow.service.WorkloadLiveActivationGate;
import com.hunt.otziv.workload_shadow.service.WorkloadLiveSettingsService;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferExecutionService;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferRollbackService;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferLiveMonitorService;
import com.hunt.otziv.workload_shadow.service.WorkloadEmergencyRollbackService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowMonitorService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowSettingsService;
import com.hunt.otziv.workload_shadow.health.service.WorkloadShadowHealthService;
import com.hunt.otziv.workload_shadow.health.dto.WorkloadShadowHealthSnapshot;
import com.hunt.otziv.workload_shadow.maintenance.service.WorkloadShadowMaintenanceService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/workload-shadow")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiWorkloadShadowController {

    private final WorkloadShadowSettingsService settingsService;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final WorkloadLiveActivationGate liveActivationGate;
    private final WorkloadShadowMonitorService monitorService;
    private final WorkloadShadowCoordinator coordinator;
    private final WorkloadShadowHealthService healthService;
    private final WorkloadShadowMaintenanceService maintenanceService;
    private final WorkloadTransferExecutionService transferExecutionService;
    private final WorkloadTransferRollbackService transferRollbackService;
    private final WorkloadTransferLiveMonitorService transferLiveMonitorService;
    private final WorkloadEmergencyRollbackService emergencyRollbackService;

    @GetMapping("/settings")
    public WorkloadShadowSettingsResponse settings() {
        return settingsService.current();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('OWNER')")
    public WorkloadShadowSettingsResponse updateSettings(@RequestBody WorkloadShadowSettingsRequest request) {
        return settingsService.update(request);
    }

    @GetMapping("/live/settings")
    public WorkloadLiveSettingsResponse liveSettings() {
        return liveSettingsService.current();
    }

    @PutMapping("/live/settings")
    @PreAuthorize("hasRole('OWNER')")
    public WorkloadLiveSettingsResponse updateLiveSettings(
            @RequestBody WorkloadLiveSettingsRequest request
    ) {
        return liveSettingsService.updateOperationalSettings(request);
    }

    @GetMapping("/live/readiness")
    public WorkloadLiveReadinessResponse liveReadiness(
            @RequestParam(value = "targetMode", defaultValue = "CANARY") String targetMode
    ) {
        return liveActivationGate.readiness(targetMode, liveSettingsService.current());
    }

    @PostMapping("/live/activate")
    @PreAuthorize("hasRole('OWNER')")
    public WorkloadLiveSettingsResponse activateLive(
            @RequestBody WorkloadLiveActivationRequest request
    ) {
        return liveSettingsService.activate(request);
    }

    @PostMapping("/live/stop")
    public WorkloadLiveSettingsResponse stopLive(
            @RequestBody WorkloadLiveStopRequest request
    ) {
        return liveSettingsService.emergencyStop(request == null ? null : request.revision());
    }

    @PostMapping("/live/workflows/{workflowId}/confirm")
    @PreAuthorize("hasRole('OWNER')")
    public WorkloadTransferActionResponse confirmTransfer(
            @PathVariable long workflowId,
            @RequestBody WorkloadTransferOwnerConfirmationRequest request
    ) {
        String confirmation = request == null ? "" : request.confirmation();
        if (!"ПОДТВЕРЖДАЮ ПЕРЕДАЧУ".equals(
                confirmation == null ? "" : confirmation.trim()
        )) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                    "Для подтверждения введите точную фразу: ПОДТВЕРЖДАЮ ПЕРЕДАЧУ"
            );
        }
        transferExecutionService.confirmByOwner(workflowId);
        return new WorkloadTransferActionResponse(
                workflowId,
                "ACCEPTED",
                "Передача подтверждена владельцем и ожидает атомарного применения"
        );
    }

    @PostMapping("/live/executions/{executionId}/rollback")
    @PreAuthorize("hasRole('OWNER')")
    public WorkloadTransferActionResponse rollbackTransfer(
            @PathVariable long executionId,
            @RequestBody WorkloadTransferRollbackRequest request
    ) {
        return transferRollbackService.rollback(
                executionId,
                request == null ? null : request.confirmation()
        );
    }

    @PostMapping("/live/emergency-assignments/{assignmentId}/rollback")
    @PreAuthorize("hasRole('OWNER')")
    public WorkloadTransferActionResponse rollbackEmergencyAssignment(
            @PathVariable long assignmentId,
            @RequestBody WorkloadTransferRollbackRequest request
    ) {
        return emergencyRollbackService.rollback(
                assignmentId,
                request == null ? null : request.confirmation()
        );
    }

    @GetMapping("/live/workflows")
    public List<WorkloadTransferWorkflowResponse> liveWorkflows(
            @RequestParam(value = "managerId", required = false) Long managerId
    ) {
        return transferLiveMonitorService.workflows(managerId);
    }

    @GetMapping("/live/executions")
    public List<WorkloadTransferExecutionResponse> liveExecutions(
            @RequestParam(value = "managerId", required = false) Long managerId
    ) {
        return transferLiveMonitorService.executions(managerId);
    }

    @GetMapping("/live/emergency-assignments")
    public List<WorkloadEmergencyAssignmentResponse> liveEmergencyAssignments(
            @RequestParam(value = "managerId", required = false) Long managerId
    ) {
        return transferLiveMonitorService.emergencyAssignments(managerId);
    }

    @GetMapping("/monitor/summary")
    public WorkloadShadowSummaryResponse summary() {
        return monitorService.summary();
    }

    @GetMapping("/monitor/workers")
    public List<WorkloadShadowWorkerResponse> workers(
            @RequestParam(value = "managerId", required = false) Long managerId
    ) {
        return monitorService.workers(managerId);
    }

    @GetMapping("/monitor/proposals")
    public List<WorkloadShadowTransferCaseResponse> proposals(
            @RequestParam(value = "managerId", required = false) Long managerId
    ) {
        return monitorService.transferCases(managerId);
    }

    @GetMapping("/monitor/events")
    public List<WorkloadShadowEventResponse> events(
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return monitorService.events(limit);
    }

    @GetMapping("/monitor/health")
    public WorkloadShadowHealthSnapshot health() {
        return healthService.snapshot();
    }

    @PostMapping("/monitor/recalculate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public WorkloadShadowRunResponse recalculate() {
        return coordinator.recalculate("MANUAL");
    }

    @PostMapping("/monitor/repair")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public WorkloadShadowMaintenanceService.RepairSummary repair() {
        return maintenanceService.repairStaleState();
    }
}
