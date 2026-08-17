package com.hunt.otziv.payments.controller;

import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentContextResponse;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSourceConfirmationRequest;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinksPageResponse;
import com.hunt.otziv.payments.dto.CloseManualPaymentUnpaidRequest;
import com.hunt.otziv.payments.dto.ConfirmManualCardPaymentRequest;
import com.hunt.otziv.payments.dto.CreateManualPaymentTaskRequest;
import com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryResponse;
import com.hunt.otziv.payments.dto.ManualPaymentTaskAccountingTargetOption;
import com.hunt.otziv.payments.dto.ManualPaymentTaskResponse;
import com.hunt.otziv.payments.dto.PaymentLinkArchiveRunResponse;
import com.hunt.otziv.payments.dto.ReportManualCardPaymentRequest;
import com.hunt.otziv.payments.dto.ResolveAmbiguousBankInitRequest;
import com.hunt.otziv.payments.dto.TbankClientPaymentModeResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfilesResponse;
import com.hunt.otziv.payments.dto.TbankRuntimeSettingsResponse;
import com.hunt.otziv.payments.dto.UpdateManagerPaymentProfilesRequest;
import com.hunt.otziv.payments.dto.UpdateManualPaymentTaskRequest;
import com.hunt.otziv.payments.dto.UpdateManualPaymentTaskStatusRequest;
import com.hunt.otziv.payments.dto.UpdatePaymentProfilePoliciesRequest;
import com.hunt.otziv.payments.dto.UpdateTbankClientPaymentModeRequest;
import com.hunt.otziv.payments.dto.UpdateTbankRuntimeSettingsRequest;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentLinkService paymentLinkService;
    private final PaymentProfileService paymentProfileService;
    private final TbankRuntimeSettingsService runtimeSettingsService;
    private final ManualPaymentTaskService manualPaymentTaskService;
    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/tbank-links")
    public AdminPaymentLinksPageResponse tbankLinks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "LIVE") String source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return paymentLinkService.adminLinks(page, size, status, search, from, to, source);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/tbank-links/archive/run")
    public PaymentLinkArchiveRunResponse archiveClosedPaymentLinks(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) Integer batchSize
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManageAllPaymentLinks();
        return paymentLinkService.archiveClosedLinks(dryRun, batchSize);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/tbank-links/{linkId}/cancel")
    public AdminPaymentLinkResponse cancelTbankPayment(@PathVariable Long linkId) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        return paymentLinkService.cancel(linkId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/tbank-links/{linkId}/confirm-manual-card-payment")
    public ResponseEntity<AdminPaymentLinkResponse> confirmManualCardPayment(
            @PathVariable Long linkId,
            @RequestBody ConfirmManualCardPaymentRequest request,
            Authentication authentication
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        AdminPaymentLinkResponse response = paymentLinkService.confirmPaidByManualCardTransfer(
                linkId,
                request != null && Boolean.TRUE.equals(request.recipientStatementChecked()),
                request != null && Boolean.TRUE.equals(request.paymentReceived()),
                request == null ? null : request.receivedAmountKopecks(),
                request == null ? null : request.note(),
                request == null ? null : request.receiptUrl(),
                request == null ? null : request.recipientType(),
                request == null ? null : request.recipientProfileId(),
                request == null ? null : request.recipientKey(),
                actor(authentication),
                authentication
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @GetMapping("/api/manager/orders/{orderId}/manual-card-payment-context")
    public ResponseEntity<ManualCardPaymentContextResponse> manualCardPaymentContext(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(paymentLinkService.manualCardPaymentContextForOrder(orderId, authentication));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/manager/orders/{orderId}/confirm-manual-card-payment")
    public void confirmOrderManualCardPayment(
            @PathVariable Long orderId,
            @RequestBody ReportManualCardPaymentRequest request,
            Authentication authentication
    ) {
        paymentLinkService.reportPaidByManualCardTransferForOrder(
                orderId,
                request == null ? null : request.reason(),
                request == null ? null : request.receiptUrl(),
                request == null ? null : request.recipientType(),
                request == null ? null : request.recipientProfileId(),
                request == null ? null : request.recipientKey(),
                actor(authentication),
                authentication
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/tbank-links/{linkId}/resolve-ambiguous-init")
    public AdminPaymentLinkResponse resolveAmbiguousTbankInit(
            @PathVariable Long linkId,
            @RequestBody ResolveAmbiguousBankInitRequest request,
            Authentication authentication
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        return paymentLinkService.releaseAmbiguousBankInit(
                linkId,
                request != null && Boolean.TRUE.equals(request.bankPaymentAbsent()),
                request == null ? null : request.note(),
                actor(authentication)
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-links/{linkId}/confirm")
    public AdminPaymentLinkResponse confirmManualPayment(
            @PathVariable Long linkId,
            Authentication authentication
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        return paymentLinkService.confirmManual(linkId, actor(authentication));
    }

    /**
     * Confirms statement evidence against this exact immutable contractor
     * payment source. Unlike the legacy full-confirm action it can safely
     * process a partial or late transfer without attributing it to a newer
     * active link of the same order.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-links/{linkId}/contractor-confirmation")
    public AdminPaymentLinkResponse confirmContractorPaymentSource(
            @PathVariable Long linkId,
            @Valid @RequestBody ContractorPaymentSourceConfirmationRequest request,
            Authentication authentication
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        return paymentLinkService.confirmContractorPaymentSource(
                linkId,
                request.confirmedTotalKopecks(),
                request.effectiveAt(),
                request.reason(),
                actor(authentication)
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-links/{linkId}/close-unpaid")
    public AdminPaymentLinkResponse closeManualPaymentAsUnpaid(
            @PathVariable Long linkId,
            @RequestBody CloseManualPaymentUnpaidRequest request,
            Authentication authentication
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        return paymentLinkService.closeManualAsUnpaid(
                linkId,
                request != null && Boolean.TRUE.equals(request.recipientStatementChecked()),
                request != null && Boolean.TRUE.equals(request.paymentAbsent()),
                request == null ? null : request.note(),
                actor(authentication),
                authentication
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-links/{linkId}/receipt")
    public AdminPaymentLinkResponse markManualPaymentReceipt(
            @PathVariable Long linkId,
            Authentication authentication
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        return paymentLinkService.markManualReceipt(linkId, actor(authentication));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-links/{linkId}/receipt/legacy-not-required")
    public AdminPaymentLinkResponse markLegacyManualPaymentReceiptNotRequired(
            @PathVariable Long linkId,
            Authentication authentication
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        return paymentLinkService.markManualReceiptLegacyNotRequired(linkId, actor(authentication));
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
    public ResponseEntity<List<ManualPaymentTaskResponse>> manualPaymentTasks() {
        return noStore(manualPaymentTaskService.managementTasks());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/manual-tasks/accounting-targets")
    public ResponseEntity<List<ManualPaymentTaskAccountingTargetOption>> manualPaymentTaskAccountingTargets(
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) Long targetAmountKopecks,
            @RequestParam(required = false) Long taskId
    ) {
        return noStore(manualPaymentTaskService.managementAccountingTargetOptions(
                managerId, targetAmountKopecks, taskId));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/manual-recipients/monthly-summary")
    public ResponseEntity<ManualPaymentRecipientMonthlySummaryResponse> manualRecipientMonthlySummary(
            @RequestParam(required = false) String month
    ) {
        return noStore(manualPaymentTaskService.recipientMonthlySummary(month));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/manual-tasks")
    public ResponseEntity<ManualPaymentTaskResponse> createManualPaymentTask(
            @RequestBody CreateManualPaymentTaskRequest request,
            Authentication authentication
    ) {
        return noStore(manualPaymentTaskService.createManagementTask(request, actor(authentication)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PutMapping("/api/admin/payments/manual-tasks/{taskId}/status")
    public ResponseEntity<ManualPaymentTaskResponse> updateManualPaymentTaskStatus(
            @PathVariable Long taskId,
            @RequestBody UpdateManualPaymentTaskStatusRequest request,
            Authentication authentication
    ) {
        return noStore(manualPaymentTaskService.updateManagementTaskStatus(
                taskId,
                request == null ? null : request.status(),
                actor(authentication)
        ));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PutMapping("/api/admin/payments/manual-tasks/{taskId}")
    public ResponseEntity<ManualPaymentTaskResponse> updateManualPaymentTask(
            @PathVariable Long taskId,
            @RequestBody UpdateManualPaymentTaskRequest request,
            Authentication authentication
    ) {
        return noStore(manualPaymentTaskService.updateManagementTask(
                taskId,
                request,
                actor(authentication)
        ));
    }

    private TbankClientPaymentModeResponse clientPaymentModeResponse(TbankRuntimeSettingsResponse settings) {
        return new TbankClientPaymentModeResponse(
                settings.clientTbankEnabled(),
                settings.paymentInstructionSource()
        );
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "admin" : authentication.getName();
    }
}
