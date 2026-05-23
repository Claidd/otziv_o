package com.hunt.otziv.whatsapp.controller;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.dto.WhatsAppReplyDTO;
import com.hunt.otziv.whatsapp.service.service.ReplyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final ReplyService replyService;

    @Value("${whatsapp.webhook.secret:}")
    private String webhookSecret;

    @PostMapping("/whatsapp-reply")
    public ResponseEntity<Void> handleReply(
            HttpServletRequest request,
            @RequestHeader(value = "X-WhatsApp-Webhook-Secret", required = false) String providedSecret,
            @RequestBody WhatsAppReplyDTO reply
    ) {
        if (!isWebhookAllowed(providedSecret)) {
            log.warn("WhatsApp personal webhook rejected from {}", request.getRemoteAddr());
            return ResponseEntity.status(401).build();
        }
        log.info("📥 Получен ответ от {}: {}", request.getRemoteAddr(), reply);
        replyService.processIncomingReply(reply);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/whatsapp-group-reply")
    public ResponseEntity<Void> handleGroupReply(
            HttpServletRequest request,
            @RequestHeader(value = "X-WhatsApp-Webhook-Secret", required = false) String providedSecret,
            @RequestBody WhatsAppGroupReplyDTO groupReply
    ) {
        if (!isWebhookAllowed(providedSecret)) {
            log.warn("WhatsApp group webhook rejected from {}", request.getRemoteAddr());
            return ResponseEntity.status(401).build();
        }
        replyService.processGroupReply(groupReply);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Webhook работает");
    }

    private boolean isWebhookAllowed(String providedSecret) {
        return webhookSecret == null
                || webhookSecret.isBlank()
                || webhookSecret.equals(providedSecret);
    }

}


