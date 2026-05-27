package com.hunt.otziv.payments;

import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.CreateManualPaymentTaskRequest;
import com.hunt.otziv.payments.dto.ManualPaymentTaskResponse;
import com.hunt.otziv.payments.dto.TbankClientPaymentModeResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfilesResponse;
import com.hunt.otziv.payments.dto.TbankRuntimeSettingsResponse;
import com.hunt.otziv.payments.dto.UpdateManualPaymentTaskStatusRequest;
import com.hunt.otziv.payments.dto.UpdateManagerPaymentProfilesRequest;
import com.hunt.otziv.payments.dto.UpdatePaymentProfilePoliciesRequest;
import com.hunt.otziv.payments.dto.UpdateTbankClientPaymentModeRequest;
import com.hunt.otziv.payments.dto.UpdateTbankRuntimeSettingsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final ManualPaymentTaskService manualPaymentTaskService;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/tbank-links")
    public List<AdminPaymentLinkResponse> tbankLinks() {
        return paymentLinkService.adminLinks();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/tbank-links/{linkId}/cancel")
    public AdminPaymentLinkResponse cancelTbankPayment(@PathVariable Long linkId) {
        return paymentLinkService.cancel(linkId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-links/{linkId}/confirm")
    public AdminPaymentLinkResponse confirmManualPayment(
            @PathVariable Long linkId,
            Authentication authentication
    ) {
        return paymentLinkService.confirmManual(linkId, actor(authentication));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-links/{linkId}/receipt")
    public AdminPaymentLinkResponse markManualPaymentReceipt(
            @PathVariable Long linkId,
            Authentication authentication
    ) {
        return paymentLinkService.markManualReceipt(linkId, actor(authentication));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/tbank-profiles")
    public TbankPaymentProfilesResponse tbankProfiles() {
        return paymentProfileService.managementState();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/tbank-client-payment-mode")
    public TbankClientPaymentModeResponse tbankClientPaymentMode() {
        return clientPaymentModeResponse(runtimeSettingsService.response());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PutMapping("/api/admin/payments/tbank-client-payment-mode")
    public TbankClientPaymentModeResponse updateTbankClientPaymentMode(
            @RequestBody UpdateTbankClientPaymentModeRequest request
    ) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        return clientPaymentModeResponse(runtimeSettingsService.updateClientPaymentSource(enabled));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/tbank-runtime-settings")
    public TbankRuntimeSettingsResponse tbankRuntimeSettings() {
        return runtimeSettingsService.response();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PutMapping("/api/admin/payments/tbank-runtime-settings")
    public TbankRuntimeSettingsResponse updateTbankRuntimeSettings(
            @RequestBody UpdateTbankRuntimeSettingsRequest request
    ) {
        return runtimeSettingsService.update(request);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PutMapping("/api/admin/payments/tbank-profiles/manager-assignments")
    public TbankPaymentProfilesResponse updateTbankProfileAssignments(
            @RequestBody UpdateManagerPaymentProfilesRequest request
    ) {
        return paymentProfileService.updateManagerAssignments(request);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PutMapping("/api/admin/payments/tbank-profiles/policies")
    public TbankPaymentProfilesResponse updatePaymentProfilePolicies(
            @RequestBody UpdatePaymentProfilePoliciesRequest request
    ) {
        return paymentProfileService.updateProfilePolicies(request);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/manual-tasks")
    public List<ManualPaymentTaskResponse> manualPaymentTasks() {
        return manualPaymentTaskService.managementTasks();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-tasks")
    public ManualPaymentTaskResponse createManualPaymentTask(
            @RequestBody CreateManualPaymentTaskRequest request,
            Authentication authentication
    ) {
        return manualPaymentTaskService.createManagementTask(request, actor(authentication));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PutMapping("/api/admin/payments/manual-tasks/{taskId}/status")
    public ManualPaymentTaskResponse updateManualPaymentTaskStatus(
            @PathVariable Long taskId,
            @RequestBody UpdateManualPaymentTaskStatusRequest request,
            Authentication authentication
    ) {
        return manualPaymentTaskService.updateManagementTaskStatus(
                taskId,
                request == null ? null : request.status(),
                actor(authentication)
        );
    }

    private TbankClientPaymentModeResponse clientPaymentModeResponse(TbankRuntimeSettingsResponse settings) {
        return new TbankClientPaymentModeResponse(
                settings.clientTbankEnabled(),
                settings.paymentInstructionSource()
        );
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "admin" : authentication.getName();
    }
}
