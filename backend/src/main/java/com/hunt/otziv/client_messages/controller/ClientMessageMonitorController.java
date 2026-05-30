package com.hunt.otziv.client_messages.controller;

import com.hunt.otziv.client_messages.dto.ClientMessageMaintenanceApplyResponse;
import com.hunt.otziv.client_messages.dto.ClientMessageMaintenancePreviewResponse;
import com.hunt.otziv.client_messages.dto.ClientMessageMonitorResponse;
import com.hunt.otziv.client_messages.dto.ClientMessageMonitorSettingsRequest;
import com.hunt.otziv.client_messages.dto.ClientMessageMonitorSettingsResponse;
import com.hunt.otziv.client_messages.service.ClientMessageMaintenancePreviewService;
import com.hunt.otziv.client_messages.service.ClientMessageMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ClientMessageMonitorController {

    private final ClientMessageMonitorService monitorService;
    private final ClientMessageMaintenancePreviewService maintenancePreviewService;

    @GetMapping("/api/admin/client-messages/monitor")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageMonitorResponse monitor() {
        return monitorService.snapshot();
    }

    @GetMapping("/api/admin/client-messages/maintenance-preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageMaintenancePreviewResponse maintenancePreview() {
        return maintenancePreviewService.preview();
    }

    @PostMapping("/api/admin/client-messages/maintenance/company-statuses")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMessageMaintenanceApplyResponse applyCompanyStatuses() {
        return maintenancePreviewService.applyCompanyStatuses();
    }

    @PostMapping("/api/admin/client-messages/maintenance/payment-overdue")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMessageMaintenanceApplyResponse applyPaymentOverdue() {
        return maintenancePreviewService.applyPaymentOverdue();
    }

    @PostMapping("/api/admin/client-messages/maintenance/missing-bad-tasks")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMessageMaintenanceApplyResponse applyMissingBadTasks() {
        return maintenancePreviewService.applyMissingBadTasks();
    }

    @PostMapping("/api/admin/client-messages/maintenance/archive-offers")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMessageMaintenanceApplyResponse blockInvalidArchiveOffers() {
        return maintenancePreviewService.blockInvalidArchiveOffers();
    }

    @PostMapping("/api/admin/client-messages/maintenance/publication-dates")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMessageMaintenanceApplyResponse repairPublicationDates() {
        return maintenancePreviewService.repairPublicationDates();
    }

    @PostMapping("/api/admin/client-messages/maintenance/publication-completed")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMessageMaintenanceApplyResponse completePublishedPublicationOrders() {
        return maintenancePreviewService.completePublishedPublicationOrders();
    }

    @PutMapping("/api/admin/client-messages/monitor")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageMonitorSettingsResponse updateMonitor(
            @RequestBody ClientMessageMonitorSettingsRequest request
    ) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        return new ClientMessageMonitorSettingsResponse(monitorService.setMonitorEnabled(enabled));
    }

    @PostMapping("/api/admin/client-messages/monitor/{stateId}/retry-now")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageMonitorResponse retryNow(@PathVariable Long stateId) {
        return monitorService.retryNow(stateId);
    }

    @PostMapping("/api/admin/client-messages/monitor/{stateId}/disable")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageMonitorResponse disable(@PathVariable Long stateId) {
        return monitorService.disable(stateId);
    }

    @PostMapping("/api/admin/client-messages/monitor/{stateId}/done")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageMonitorResponse markDone(@PathVariable Long stateId) {
        return monitorService.markDone(stateId);
    }
}
