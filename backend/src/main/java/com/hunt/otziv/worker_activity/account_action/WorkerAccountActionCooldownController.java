package com.hunt.otziv.worker_activity.account_action;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WorkerAccountActionCooldownController {
    private final WorkerAccountActionCooldownService service;

    @GetMapping("/api/worker/account-action-cooldown")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<WorkerAccountActionCooldownState> currentState() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.currentState());
    }
}
