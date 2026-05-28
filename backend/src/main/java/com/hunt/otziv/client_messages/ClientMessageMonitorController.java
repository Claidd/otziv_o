package com.hunt.otziv.client_messages;

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

    @GetMapping("/api/admin/client-messages/monitor")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageMonitorResponse monitor() {
        return monitorService.snapshot();
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
