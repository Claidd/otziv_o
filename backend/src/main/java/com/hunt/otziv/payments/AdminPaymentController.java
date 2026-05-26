package com.hunt.otziv.payments;

import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.TbankClientPaymentModeResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfilesResponse;
import com.hunt.otziv.payments.dto.TbankRuntimeSettingsResponse;
import com.hunt.otziv.payments.dto.UpdateManagerPaymentProfilesRequest;
import com.hunt.otziv.payments.dto.UpdateTbankClientPaymentModeRequest;
import com.hunt.otziv.payments.dto.UpdateTbankRuntimeSettingsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentLinkService paymentLinkService;
    private final PaymentProfileService paymentProfileService;
    private final TbankRuntimeSettingsService runtimeSettingsService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/payments/tbank-links")
    public List<AdminPaymentLinkResponse> tbankLinks() {
        return paymentLinkService.adminLinks();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/payments/tbank-links/{linkId}/cancel")
    public AdminPaymentLinkResponse cancelTbankPayment(@PathVariable Long linkId) {
        return paymentLinkService.cancel(linkId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/payments/tbank-profiles")
    public TbankPaymentProfilesResponse tbankProfiles() {
        return paymentProfileService.managementState();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/payments/tbank-client-payment-mode")
    public TbankClientPaymentModeResponse tbankClientPaymentMode() {
        return clientPaymentModeResponse(runtimeSettingsService.response());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/api/admin/payments/tbank-client-payment-mode")
    public TbankClientPaymentModeResponse updateTbankClientPaymentMode(
            @RequestBody UpdateTbankClientPaymentModeRequest request
    ) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        return clientPaymentModeResponse(runtimeSettingsService.updateClientPaymentSource(enabled));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/payments/tbank-runtime-settings")
    public TbankRuntimeSettingsResponse tbankRuntimeSettings() {
        return runtimeSettingsService.response();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/api/admin/payments/tbank-runtime-settings")
    public TbankRuntimeSettingsResponse updateTbankRuntimeSettings(
            @RequestBody UpdateTbankRuntimeSettingsRequest request
    ) {
        return runtimeSettingsService.update(request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/api/admin/payments/tbank-profiles/manager-assignments")
    public TbankPaymentProfilesResponse updateTbankProfileAssignments(
            @RequestBody UpdateManagerPaymentProfilesRequest request
    ) {
        return paymentProfileService.updateManagerAssignments(request);
    }

    private TbankClientPaymentModeResponse clientPaymentModeResponse(TbankRuntimeSettingsResponse settings) {
        return new TbankClientPaymentModeResponse(
                settings.clientTbankEnabled(),
                settings.paymentInstructionSource()
        );
    }
}
