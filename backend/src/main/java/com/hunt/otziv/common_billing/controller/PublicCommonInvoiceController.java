package com.hunt.otziv.common_billing.controller;

import com.hunt.otziv.common_billing.dto.PublicCommonInvoiceResponse;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.payments.dto.PublicPaymentInitRequest;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicCommonInvoiceController {

    private final CommonBillingService commonBillingService;

    @GetMapping("/api/payments/public/group/{token}")
    public PublicCommonInvoiceResponse commonInvoice(@PathVariable String token) {
        return commonBillingService.publicInvoice(token);
    }

    @PostMapping("/api/payments/public/group/{token}/init")
    public PublicPaymentInitResponse initCommonInvoicePayment(
            @PathVariable String token,
            @Valid @RequestBody PublicPaymentInitRequest request
    ) {
        return commonBillingService.initPublicPayment(
                token,
                request.email(),
                Boolean.TRUE.equals(request.offerConsent()),
                Boolean.TRUE.equals(request.privacyConsent()),
                Boolean.TRUE.equals(request.receiptConsent())
        );
    }
}
