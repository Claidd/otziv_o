package com.hunt.otziv.integration.outbox;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/integration-outbox")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class IntegrationOutboxStatusController {

    private final IntegrationOutboxStatusService statusService;

    IntegrationOutboxStatusController(IntegrationOutboxStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public IntegrationOutboxStatusResponse status() {
        return statusService.snapshot();
    }
}
