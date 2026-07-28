package com.hunt.otziv.workload_shadow.controller;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowEventResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowRunResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSummaryResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowTransferCaseResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowWorkerResponse;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowCoordinator;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowMonitorService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowSettingsService;
import com.hunt.otziv.workload_shadow.health.WorkloadShadowHealthService;
import com.hunt.otziv.workload_shadow.health.WorkloadShadowHealthSnapshot;
import com.hunt.otziv.workload_shadow.maintenance.WorkloadShadowMaintenanceService;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/workload-shadow")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiWorkloadShadowController {

    private final WorkloadShadowSettingsService settingsService;
    private final WorkloadShadowMonitorService monitorService;
    private final WorkloadShadowCoordinator coordinator;
    private final WorkloadShadowHealthService healthService;
    private final WorkloadShadowMaintenanceService maintenanceService;

    @GetMapping("/settings")
    public WorkloadShadowSettingsResponse settings() {
        return settingsService.current();
    }

    @PutMapping("/settings")
    public WorkloadShadowSettingsResponse updateSettings(@RequestBody WorkloadShadowSettingsRequest request) {
        return settingsService.update(request);
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
