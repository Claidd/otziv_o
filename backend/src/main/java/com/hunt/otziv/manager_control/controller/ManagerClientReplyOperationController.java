package com.hunt.otziv.manager_control.controller;

import com.hunt.otziv.manager_control.dto.ManagerClientReplyResolutionRequest;
import com.hunt.otziv.manager_control.dto.ManagerClientReplyResolutionResponse;
import com.hunt.otziv.manager_control.service.ManagerControlClientReplyWorkflow;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/manager-control/concrete-items/{cardId}/reply-operations/{operationId}")
public class ManagerClientReplyOperationController {
    private final ManagerControlClientReplyWorkflow workflow;

    /** Reads provider evidence only; this endpoint never dispatches or retries a message. */
    @PostMapping("/reconcile")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ManagerClientReplyResolutionResponse reconcile(@PathVariable Long cardId, @PathVariable String operationId,
            @RequestBody ManagerClientReplyResolutionRequest request, Principal principal, Authentication authentication) {
        return workflow.reconcile(cardId, operationId, request, principal, authentication);
    }
}
