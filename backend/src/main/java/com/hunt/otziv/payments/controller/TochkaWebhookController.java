package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.service.PaymentLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TochkaWebhookController {

    private final PaymentLinkService paymentLinkService;
    private final com.hunt.otziv.paper_billing.PaperBillingSettlement paperBilling;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    @PostMapping(
            value = "/api/payments/tochka/webhook",
            consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<String> webhook(@RequestBody String rawJwt) {
        // This untrusted hint only chooses a verifier. Both handlers verify the bank signature
        // and bind the signed type and exact payment identity before any state mutation.
        boolean incoming = false;
        try {
            String[] parts = rawJwt.split("\\.", -1);
            if (parts.length == 3 && rawJwt.length() <= 65536)
                incoming = "incomingPayment".equals(json.readTree(java.util.Base64.getUrlDecoder().decode(parts[1])).path("webhookType").asText());
        } catch (Exception ignored) { }
        if (incoming) paperBilling.webhook(rawJwt);
        else paymentLinkService.handleTochkaWebhook(rawJwt);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("OK");
    }
}
