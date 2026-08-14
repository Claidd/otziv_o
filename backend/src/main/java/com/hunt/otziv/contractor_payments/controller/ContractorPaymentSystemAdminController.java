package com.hunt.otziv.contractor_payments.controller;

import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRoutingCommandRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemActivationRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemStatusResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardManualResolutionRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardReconciliationApplyRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardReconciliationResponse;
import com.hunt.otziv.contractor_payments.service.ContractorLegacyRewardReconciliationService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentSystemAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/contractor-payments/system")
public class ContractorPaymentSystemAdminController {

    private final ContractorPaymentSystemAdminService service;
    private final ContractorLegacyRewardReconciliationService legacyRewardReconciliationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ContractorPaymentSystemStatusResponse status() {
        return service.status();
    }

    @PostMapping("/activate")
    @PreAuthorize("hasRole('OWNER')")
    public ContractorPaymentSystemStatusResponse activate(
            @Valid @RequestBody ContractorPaymentSystemActivationRequest request
    ) {
        service.activate(request);
        return service.status();
    }

    @PostMapping("/routing")
    @PreAuthorize("hasRole('OWNER')")
    public ContractorPaymentSystemStatusResponse updateRouting(
            @Valid @RequestBody ContractorPaymentRoutingCommandRequest request
    ) {
        service.updateRouting(request);
        return service.status();
    }

    @GetMapping("/legacy-reconciliation")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ContractorLegacyRewardReconciliationResponse latestLegacyReconciliation() {
        return legacyRewardReconciliationService.latest();
    }

    @PostMapping("/legacy-reconciliation/prepare")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ContractorLegacyRewardReconciliationResponse prepareLegacyReconciliation() {
        return legacyRewardReconciliationService.prepare();
    }

    @PostMapping("/legacy-reconciliation/{runId}/apply")
    @PreAuthorize("hasRole('OWNER')")
    public ContractorLegacyRewardReconciliationResponse applyLegacyReconciliation(
            @PathVariable long runId,
            @Valid @RequestBody ContractorLegacyRewardReconciliationApplyRequest request
    ) {
        return legacyRewardReconciliationService.applyAutomatic(runId, request);
    }

    @PostMapping("/legacy-reconciliation/{runId}/orders/{orderId}/resolve")
    @PreAuthorize("hasRole('OWNER')")
    public ContractorLegacyRewardReconciliationResponse resolveLegacyReconciliation(
            @PathVariable long runId,
            @PathVariable long orderId,
            @Valid @RequestBody ContractorLegacyRewardManualResolutionRequest request
    ) {
        return legacyRewardReconciliationService.resolveManual(runId, orderId, request);
    }
}
