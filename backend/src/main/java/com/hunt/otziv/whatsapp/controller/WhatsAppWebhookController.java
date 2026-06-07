package com.hunt.otziv.whatsapp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.webhook.security.WebhookSignatureVerifier;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.dto.WhatsAppReplyDTO;
import com.hunt.otziv.whatsapp.service.service.ReplyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final ReplyService replyService;
    private final ObjectMapper objectMapper;
    private final WebhookSignatureVerifier signatureVerifier;

    @Value("${whatsapp.webhook.secret:}")
    private String webhookSecret;

    @Value("${whatsapp.webhook.hmac-required:false}")
    private boolean hmacRequired;

    @PostMapping("/whatsapp-reply")
    public ResponseEntity<Void> handleReply(
            HttpServletRequest request,
            @RequestHeader(value = "X-WhatsApp-Webhook-Secret", required = false) String providedSecret,
            @RequestHeader(value = "X-WhatsApp-Webhook-Signature", required = false) String signature,
            @RequestBody String requestBody
    ) {
        if (!hasText(webhookSecret)) {
            log.error("WhatsApp personal webhook rejected: webhook secret is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!isWebhookAllowed(providedSecret, signature, requestBody)) {
            log.warn("WhatsApp personal webhook rejected from {}", request.getRemoteAddr());
            return ResponseEntity.status(401).build();
        }

        WhatsAppReplyDTO reply = parseBody(requestBody, WhatsAppReplyDTO.class, "personal");
        if (reply == null) {
            return ResponseEntity.badRequest().build();
        }

        log.info("WhatsApp personal webhook accepted from {}", request.getRemoteAddr());
        replyService.processIncomingReply(reply);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/whatsapp-group-reply")
    public ResponseEntity<Void> handleGroupReply(
            HttpServletRequest request,
            @RequestHeader(value = "X-WhatsApp-Webhook-Secret", required = false) String providedSecret,
            @RequestHeader(value = "X-WhatsApp-Webhook-Signature", required = false) String signature,
            @RequestBody String requestBody
    ) {
        if (!hasText(webhookSecret)) {
            log.error("WhatsApp group webhook rejected: webhook secret is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!isWebhookAllowed(providedSecret, signature, requestBody)) {
            log.warn("WhatsApp group webhook rejected from {}", request.getRemoteAddr());
            return ResponseEntity.status(401).build();
        }

        WhatsAppGroupReplyDTO groupReply = parseBody(requestBody, WhatsAppGroupReplyDTO.class, "group");
        if (groupReply == null) {
            return ResponseEntity.badRequest().build();
        }

        log.info("WhatsApp group webhook accepted from {}", request.getRemoteAddr());
        replyService.processGroupReply(groupReply);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Webhook работает");
    }

    private boolean isWebhookAllowed(String providedSecret, String signature, String requestBody) {
        return signatureVerifier.verifyHeaderSecret(webhookSecret, providedSecret)
                && signatureVerifier.verifyOptionalHmacSha256(requestBody, webhookSecret, signature, hmacRequired);
    }

    private <T> T parseBody(String requestBody, Class<T> bodyType, String webhookType) {
        if (!hasText(requestBody)) {
            log.warn("WhatsApp {} webhook rejected: empty body", webhookType);
            return null;
        }
        try {
            return objectMapper.readValue(requestBody, bodyType);
        } catch (Exception e) {
            log.warn("WhatsApp {} webhook rejected: invalid JSON body", webhookType);
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

}

