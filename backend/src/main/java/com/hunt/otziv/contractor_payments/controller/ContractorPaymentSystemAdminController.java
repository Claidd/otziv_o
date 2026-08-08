package com.hunt.otziv.contractor_payments.controller;

import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRoutingCommandRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemActivationRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemStatusResponse;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentSystemAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/contractor-payments/system")
public class ContractorPaymentSystemAdminController {

    private final ContractorPaymentSystemAdminService service;

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
}
