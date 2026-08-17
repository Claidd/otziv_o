package com.hunt.otziv.common_billing.controller;

import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentOptionsResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorActualPaymentAttributionModeResponse;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionFlowPolicy;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Final manual-payment attribution for common invoices. Kept separate from
 * the legacy endpoints so an old client cannot accidentally reinterpret its
 * short free-text confirmation as an actual-recipient assertion.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/common-billing/invoices/{invoiceId}")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
public class CommonManualPaymentAttributionController {

    private final CommonBillingService commonBillingService;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    private final ContractorActualPaymentAttributionFlowPolicy flowPolicy;

    @GetMapping("/manual-payment-mode")
    public ResponseEntity<ContractorActualPaymentAttributionModeResponse> mode(@PathVariable Long invoiceId) {
        targetAccessPolicy.requireCanManageCommonInvoice(invoiceId);
        commonBillingService.invoice(invoiceId);
        return noStore(new ContractorActualPaymentAttributionModeResponse(
                flowPolicy.attributionRequired()
                        || commonBillingService.hasFrozenManualTaskRoute(invoiceId)
        ));
    }

    @GetMapping("/manual-payment-options")
    public ResponseEntity<CommonManualPaymentOptionsResponse> options(@PathVariable Long invoiceId) {
        targetAccessPolicy.requireCanManageCommonInvoice(invoiceId);
        flowPolicy.requireAttributionFlowOrFrozenTask(
                commonBillingService.hasFrozenManualTaskRoute(invoiceId));
        return noStore(commonBillingService.manualPaymentOptions(invoiceId));
    }

    @PostMapping("/paid-with-attributions")
    public ResponseEntity<CommonInvoiceDetailsResponse> markPaid(
            @PathVariable Long invoiceId,
            @Valid @RequestBody CommonManualPaymentAttributionRequest request,
            Principal principal
    ) {
        targetAccessPolicy.requireCanManageCommonInvoice(invoiceId);
        flowPolicy.requireAttributionFlowOrFrozenTask(
                commonBillingService.hasFrozenManualTaskRoute(invoiceId));
        return noStore(commonBillingService.markPaidWithAttributions(invoiceId, request, principal));
    }

    @PostMapping("/attention/manual-card-paid-with-attributions")
    public ResponseEntity<CommonInvoiceDetailsResponse> reportManualCardPayment(
            @PathVariable Long invoiceId,
            @Valid @RequestBody CommonManualPaymentAttributionRequest request,
            Principal principal
    ) {
        targetAccessPolicy.requireCanManageCommonInvoice(invoiceId);
        flowPolicy.requireAttributionFlowOrFrozenTask(
                commonBillingService.hasFrozenManualTaskRoute(invoiceId));
        return noStore(commonBillingService.reportPaidByManualCardTransferWithAttributions(
                invoiceId,
                request,
                principal
        ));
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }
}
