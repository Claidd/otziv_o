package com.hunt.otziv.common_billing.controller;

import com.hunt.otziv.common_billing.dto.CommonBillingAccountRequest;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchivePreviewResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceCloseRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceManualCardPaymentRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentInitCheckRequest;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.common_billing.service.CommonBillingPublicationApprovalFailureMarker;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionFlowPolicy;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSourceConfirmationRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CommonBillingAdminController {

    private final CommonBillingService commonBillingService;
    private final CommonBillingPublicationApprovalFailureMarker publicationApprovalFailureMarker;
    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;
    private final ContractorActualPaymentAttributionFlowPolicy actualPaymentFlowPolicy;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/common-billing/accounts")
    public List<CommonBillingAccountResponse> accounts() {
        return commonBillingService.accounts();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @GetMapping("/api/common-billing/accounts/by-company/{companyId}")
    public List<CommonBillingAccountResponse> accountsForCompany(@PathVariable Long companyId) {
        return commonBillingService.accountsForCompany(companyId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/accounts")
    public CommonBillingAccountResponse createAccount(@RequestBody CommonBillingAccountRequest request) {
        return commonBillingService.createAccount(request);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @GetMapping("/api/common-billing/accounts/{accountId}")
    public CommonBillingAccountResponse account(@PathVariable Long accountId) {
        return commonBillingService.account(accountId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PutMapping("/api/common-billing/accounts/{accountId}")
    public CommonBillingAccountResponse updateAccount(
            @PathVariable Long accountId,
            @RequestBody CommonBillingAccountRequest request
    ) {
        return commonBillingService.updateAccount(accountId, request);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/accounts/{accountId}/companies/{companyId}")
    public CommonBillingAccountResponse addCompany(@PathVariable Long accountId, @PathVariable Long companyId) {
        return commonBillingService.addCompany(accountId, companyId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @DeleteMapping("/api/common-billing/accounts/{accountId}/companies/{companyId}")
    public CommonBillingAccountResponse removeCompany(
            @PathVariable Long accountId,
            @PathVariable Long companyId,
            @RequestParam(name = "detachCurrent", defaultValue = "false") boolean detachCurrent
    ) {
        return commonBillingService.removeCompany(accountId, companyId, detachCurrent);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @GetMapping("/api/common-billing/invoices/{invoiceId}")
    public CommonInvoiceDetailsResponse invoice(@PathVariable Long invoiceId) {
        return commonBillingService.invoice(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @DeleteMapping("/api/common-billing/invoices/{invoiceId}")
    public void deleteInvoice(@PathVariable Long invoiceId, Principal principal) {
        requireCanMutateInvoice(invoiceId);
        commonBillingService.deleteInvoiceWithOrders(invoiceId, principal);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/send")
    public CommonInvoiceDetailsResponse sendInvoice(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.sendInvoice(invoiceId, true);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/remind")
    public CommonInvoiceDetailsResponse remind(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.sendManualReminder(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/paid")
    public CommonInvoiceDetailsResponse markPaid(
            @PathVariable Long invoiceId,
            @RequestBody ManualPaymentConfirmationRequest request,
            Principal principal
    ) {
        requireCanMutateInvoice(invoiceId);
        actualPaymentFlowPolicy.requireLegacyFlow();
        return commonBillingService.markPaid(invoiceId, request, principal);
    }

    /**
     * Statement evidence bound to this immutable common-invoice contractor
     * source. A manager must not turn a generic "paid" action into evidence
     * for a specialist or manager recipient.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/contractor-confirmation")
    public CommonInvoiceDetailsResponse confirmContractorPaymentSource(
            @PathVariable Long invoiceId,
            @Valid @RequestBody ContractorPaymentSourceConfirmationRequest request,
            Principal principal
    ) {
        contractorPaymentTargetAccessPolicy.requireCanManageCommonInvoice(invoiceId);
        return commonBillingService.confirmContractorPaymentSource(
                invoiceId,
                request.confirmedTotalKopecks(),
                request.effectiveAt(),
                request.reason(),
                principal
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/attention/retry")
    public CommonInvoiceDetailsResponse retryAttention(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.retryAttention(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/attention/resolve")
    public CommonInvoiceDetailsResponse resolveAttention(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.resolveAttention(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/attention/repair-payment-route")
    public CommonInvoiceDetailsResponse repairPaymentRoute(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.repairStandalonePaymentRouteConflict(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/attention/manual-card-paid")
    public CommonInvoiceDetailsResponse reportManualCardPayment(
            @PathVariable Long invoiceId,
            @RequestBody CommonInvoiceManualCardPaymentRequest request,
            Principal principal
    ) {
        requireCanMutateInvoice(invoiceId);
        actualPaymentFlowPolicy.requireLegacyFlow();
        return commonBillingService.reportPaidByManualCardTransfer(invoiceId, request, principal);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/technical-tail/resolve")
    public CommonInvoiceDetailsResponse resolveTechnicalTail(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.resolveTechnicalTail(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/payment-notification/resolve")
    public CommonInvoiceDetailsResponse resolvePaymentSuccessNotification(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.resolvePaymentSuccessNotification(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/attention/apply-late-payment")
    public CommonInvoiceDetailsResponse applyLatePayment(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.applyLatePayment(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/attention/confirm-final-cancel-check")
    public CommonInvoiceDetailsResponse confirmFinalPaymentCancelCheck(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.confirmFinalPaymentCancelCheck(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/attention/confirm-payment-init-check")
    public CommonInvoiceDetailsResponse confirmPaymentInitCheck(
            @PathVariable Long invoiceId,
            @RequestBody(required = false) CommonInvoicePaymentInitCheckRequest request
    ) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.confirmPaymentInitCheck(invoiceId, request);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/unpaid")
    public CommonInvoiceDetailsResponse markUnpaid(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.markUnpaid(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/ban")
    public CommonInvoiceDetailsResponse markBan(@PathVariable Long invoiceId, Principal principal) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.markBan(invoiceId, principal);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @GetMapping("/api/common-billing/invoices/{invoiceId}/archive-preview")
    public CommonInvoiceArchivePreviewResponse archivePreview(@PathVariable Long invoiceId) {
        return commonBillingService.archivePreview(invoiceId);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/archive")
    public CommonInvoiceDetailsResponse archiveInvoice(
            @PathVariable Long invoiceId,
            @RequestBody CommonInvoiceCloseRequest request,
            Principal principal
    ) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.archiveInvoice(invoiceId, request, principal);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/orders/{orderId}/paid")
    public CommonInvoiceDetailsResponse markOrderPaid(
            @PathVariable Long invoiceId,
            @PathVariable Long orderId,
            @RequestBody ManualPaymentConfirmationRequest request,
            Principal principal
    ) {
        requireCanMutateInvoice(invoiceId);
        actualPaymentFlowPolicy.requireLegacyFlow();
        return commonBillingService.markOrderPaid(invoiceId, orderId, request, principal);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/orders/approve-review")
    public CommonInvoiceDetailsResponse approveReviewOrders(@PathVariable Long invoiceId) {
        requireCanMutateInvoice(invoiceId);
        try {
            return commonBillingService.approveReviewOrders(invoiceId);
        } catch (RuntimeException exception) {
            publicationApprovalFailureMarker.markAttention(invoiceId, exception);
            throw exception;
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @DeleteMapping("/api/common-billing/invoices/{invoiceId}/orders/{orderId}")
    public CommonInvoiceDetailsResponse detachOrder(@PathVariable Long invoiceId, @PathVariable Long orderId) {
        requireCanMutateInvoice(invoiceId);
        return commonBillingService.detachOrder(invoiceId, orderId);
    }

    private void requireCanMutateInvoice(Long invoiceId) {
        contractorPaymentTargetAccessPolicy.requireCanManageCommonInvoice(invoiceId);
    }
}
