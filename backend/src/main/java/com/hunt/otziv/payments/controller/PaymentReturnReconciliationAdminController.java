package com.hunt.otziv.payments.controller;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.dto.PaymentReturnManualResolutionRequest;
import com.hunt.otziv.payments.dto.PaymentReturnManualResolutionResponse;
import com.hunt.otziv.payments.service.PaymentReturnManualReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentReturnReconciliationAdminController {

    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;
    private final PaymentReturnManualReconciliationService reconciliationService;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/tbank-links/{linkId}/return-reconciliation/resolve")
    public ResponseEntity<PaymentReturnManualResolutionResponse> resolve(
            @PathVariable Long linkId,
            @Valid @RequestBody PaymentReturnManualResolutionRequest request
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(reconciliationService.resolve(linkId, request));
    }
}
