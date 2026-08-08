package com.hunt.otziv.contractor_payments.controller;

import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSummaryResponse;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentVisibilityService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/contractor-payments")
public class ContractorPaymentSelfController {

    private final ContractorPaymentVisibilityService visibilityService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public List<ContractorPaymentSummaryResponse> ownSummary(Authentication authentication) {
        return visibilityService.ownSummary(authentication);
    }
}
