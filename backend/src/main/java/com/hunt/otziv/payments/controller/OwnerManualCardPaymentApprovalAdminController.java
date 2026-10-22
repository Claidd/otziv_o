package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.service.OwnerManualCardPaymentApprovalReconciliationService;
import com.hunt.otziv.payments.service.OwnerManualCardPaymentApprovalRevocationService;
import com.hunt.otziv.payments.dto.RevokePendingOwnerApprovalRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerManualCardPaymentApprovalAdminController {
    private final OwnerManualCardPaymentApprovalReconciliationService reconciliation;
    private final OwnerManualCardPaymentApprovalRevocationService revocation;

    @GetMapping("/api/admin/payments/owner-approvals/by-payment-link/{paymentLinkId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public OwnerManualCardPaymentApprovalRevocationService.ApprovalView byPaymentLink(
            @PathVariable Long paymentLinkId, Authentication authentication) {
        return revocation.findByPaymentLink(paymentLinkId, authentication);
    }

    @PostMapping("/api/admin/payments/owner-approvals/{approvalId}/revoke-pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public OwnerManualCardPaymentApprovalRevocationService.RevocationResult revokePending(
            @PathVariable Long approvalId, @Valid @RequestBody RevokePendingOwnerApprovalRequest request,
            Authentication authentication) {
        return revocation.revokePending(approvalId, request, authentication);
    }

    @PostMapping("/api/admin/payments/owner-approvals/{approvalId}/close-superseded")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public OwnerManualCardPaymentApprovalReconciliationService.Result closeSuperseded(
            @PathVariable Long approvalId, Authentication authentication) {
        return reconciliation.closeSuperseded(approvalId, authentication);
    }
}
