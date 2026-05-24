package com.hunt.otziv.payments;

import com.hunt.otziv.payments.dto.PublicPaymentInitRequest;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.dto.TbankPaymentStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicPaymentController {

    private final PaymentLinkService paymentLinkService;
    private final TbankPaymentProperties properties;

    @GetMapping("/api/payments/public/tbank-status")
    public TbankPaymentStatusResponse tbankStatus() {
        return new TbankPaymentStatusResponse(
                properties.isEnabled(),
                properties.isPaymentLinksEnabled(),
                properties.isManagerUiEnabled(),
                properties.isApplyConfirmedPayments(),
                properties.hasCredentials(),
                isTestMode(),
                properties.getBaseUrl(),
                properties.getPublicBaseUrl(),
                properties.notificationUrl(),
                properties.successUrl(),
                properties.failUrl()
        );
    }

    @GetMapping("/api/payments/public/{token}")
    public PublicPaymentLinkResponse paymentLink(@PathVariable String token) {
        return paymentLinkService.publicLink(token);
    }

    @PostMapping("/api/payments/public/{token}/init")
    public PublicPaymentInitResponse initPayment(
            @PathVariable String token,
            @Valid @RequestBody PublicPaymentInitRequest request
    ) {
        return paymentLinkService.init(token, request.email());
    }

    private boolean isTestMode() {
        return properties.getBaseUrl().contains("test") || properties.getTerminalKey().endsWith("DEMO");
    }
}
