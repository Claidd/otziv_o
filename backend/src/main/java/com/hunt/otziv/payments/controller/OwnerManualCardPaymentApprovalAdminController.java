package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.service.OwnerManualCardPaymentApprovalReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerManualCardPaymentApprovalAdminController {
    private final OwnerManualCardPaymentApprovalReconciliationService reconciliation;

    @PostMapping("/api/admin/payments/owner-approvals/{approvalId}/close-superseded")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public OwnerManualCardPaymentApprovalReconciliationService.Result closeSuperseded(
            @PathVariable Long approvalId, Authentication authentication) {
        return reconciliation.closeSuperseded(approvalId, authentication);
    }
}
