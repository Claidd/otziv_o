package com.hunt.otziv.whatsapp.controller;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.dto.WhatsAppReplyDTO;
import com.hunt.otziv.whatsapp.service.service.ReplyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final ReplyService replyService;

    @PostMapping("/whatsapp-reply")
    public ResponseEntity<Void> handleReply(HttpServletRequest request, @RequestBody WhatsAppReplyDTO reply) {
        log.info("📥 Получен ответ от {}: {}", request.getRemoteAddr(), reply);
        replyService.processIncomingReply(reply);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/whatsapp-group-reply")
    public ResponseEntity<Void> handleGroupReply(@RequestBody WhatsAppGroupReplyDTO groupReply) {
        replyService.processGroupReply(groupReply);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Webhook работает");
    }

}


