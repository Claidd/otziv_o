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

    @PostMapping(
            value = "/api/payments/tochka/webhook",
            consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<String> webhook(@RequestBody String rawJwt) {
        paymentLinkService.handleTochkaWebhook(rawJwt);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("OK");
    }
}
