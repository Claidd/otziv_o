package com.hunt.otziv.common_billing.controller;

import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.service.CommonInvoiceRecordedPaymentReconciliation;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CommonInvoiceRecordedPaymentController {
    private final CommonInvoiceRecordedPaymentReconciliation reconciliation;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/reconcile-recorded-payments")
    public CommonInvoiceDetailsResponse reconcile(@PathVariable long invoiceId,
            @Valid @RequestBody CommonManualPaymentAttributionRequest request, Principal principal) {
        return reconciliation.reconcile(invoiceId, request, principal);
    }
}
