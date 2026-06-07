package com.hunt.otziv.maxbot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.maxbot.service.MaxBotUpdateService;
import com.hunt.otziv.webhook.security.WebhookSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook/max")
@RequiredArgsConstructor
@Slf4j
public class MaxBotWebhookController {

    private final MaxBotUpdateService maxBotUpdateService;
    private final ObjectMapper objectMapper;
    private final WebhookSignatureVerifier signatureVerifier;

    @Value("${max.bot.webhook-secret:}")
    private String webhookSecret;

    @Value("${max.bot.webhook.hmac-required:false}")
    private boolean hmacRequired;

    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-Max-Bot-Api-Secret", required = false) String secret,
            @RequestHeader(value = "X-Max-Bot-Webhook-Signature", required = false) String signature,
            @RequestBody String requestBody
    ) {
        if (!hasText(webhookSecret)) {
            log.error("MAX webhook rejected: webhook secret is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!signatureVerifier.verifyHeaderSecret(webhookSecret, secret)) {
            log.warn("MAX webhook rejected: invalid secret header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!signatureVerifier.verifyOptionalHmacSha256(requestBody, webhookSecret, signature, hmacRequired)) {
            log.warn("MAX webhook rejected: invalid HMAC signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode update = parseUpdate(requestBody);
        if (update == null) {
            return ResponseEntity.badRequest().build();
        }

        log.info("MAX webhook accepted");
        maxBotUpdateService.handleUpdate(update);
        return ResponseEntity.ok().build();
    }

    private JsonNode parseUpdate(String requestBody) {
        if (!hasText(requestBody)) {
            log.warn("MAX webhook rejected: empty body");
            return null;
        }
        try {
            return objectMapper.readTree(requestBody);
        } catch (Exception e) {
            log.warn("MAX webhook rejected: invalid JSON body");
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
