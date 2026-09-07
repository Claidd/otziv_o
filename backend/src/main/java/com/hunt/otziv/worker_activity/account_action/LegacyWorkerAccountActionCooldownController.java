package com.hunt.otziv.worker_activity.account_action;

import com.hunt.otziv.config.legacy.LegacyMvc;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LegacyMvc
@RequiredArgsConstructor
public class LegacyWorkerAccountActionCooldownController {
    private final WorkerAccountActionCooldownService service;

    @GetMapping("/ordersDetails/account-action-cooldown")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<WorkerAccountActionCooldownState> currentState() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.currentState());
    }
}
