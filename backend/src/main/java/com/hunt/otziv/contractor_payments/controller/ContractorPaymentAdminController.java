package com.hunt.otziv.contractor_payments.controller;

import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileAdjustmentResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorDirectSettlementRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorDirectSettlementResponse;
import com.hunt.otziv.contractor_payments.service.ContractorDirectSettlementService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users/{userId}/contractor-payment-profiles")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ContractorPaymentAdminController {

    private final ContractorPaymentProfileService profileService;
    private final ContractorDirectSettlementService directSettlementService;

    @GetMapping
    public List<ContractorPaymentProfileResponse> getProfiles(@PathVariable Long userId) {
        return profileService.getForUser(userId);
    }

    @PutMapping
    public ContractorPaymentProfileResponse updateProfile(
            @PathVariable Long userId,
            @Valid @RequestBody ContractorPaymentProfileRequest request
    ) {
        return profileService.update(userId, request);
    }

    @GetMapping("/{profileId}/opening-balance-history")
    public List<ContractorPaymentProfileAdjustmentResponse> openingBalanceHistory(
            @PathVariable Long userId,
            @PathVariable Long profileId
    ) {
        return profileService.openingBalanceHistory(userId, profileId);
    }

    @GetMapping("/{profileId}/direct-settlements")
    public List<ContractorDirectSettlementResponse> directSettlementHistory(
            @PathVariable Long userId,
            @PathVariable Long profileId
    ) {
        return directSettlementService.history(userId, profileId);
    }

    @PostMapping("/{profileId}/direct-settlements")
    public ContractorDirectSettlementResponse createDirectSettlement(
            @PathVariable Long userId,
            @PathVariable Long profileId,
            @Valid @RequestBody ContractorDirectSettlementRequest request
    ) {
        return directSettlementService.createPayment(userId, profileId, request);
    }

    @PostMapping("/{profileId}/direct-settlements/{originalSettlementId}/reversals")
    public ContractorDirectSettlementResponse reverseDirectSettlement(
            @PathVariable Long userId,
            @PathVariable Long profileId,
            @PathVariable Long originalSettlementId,
            @Valid @RequestBody ContractorDirectSettlementRequest request
    ) {
        return directSettlementService.createReversal(userId, profileId, originalSettlementId, request);
    }
}
