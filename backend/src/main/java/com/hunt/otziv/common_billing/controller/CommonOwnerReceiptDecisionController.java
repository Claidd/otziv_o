package com.hunt.otziv.common_billing.controller;

import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.contractor_payments.api.PaymentReviewTargetAuthorization;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CommonOwnerReceiptDecisionController {
    private final CommonBillingService commonBillingService;
    private final PaymentReviewTargetAuthorization access;

    @PostMapping("/api/common-billing/invoices/{invoiceId}/owner-reports/{reportId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable long invoiceId, @PathVariable long reportId, Principal actor) {
        access.requireCanManageCommonInvoice(invoiceId);
        commonBillingService.rejectOwnerReport(invoiceId, reportId, actor);
    }
}
