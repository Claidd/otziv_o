package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.service.LeadCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadSenderController {

    private final LeadCommandService commands;

    /**
     * Отправка лида с локальной машины на VPS по кнопке
     */
    @PostMapping("/sendToServer")
    public String sendLeadToServer(@RequestParam Long leadId,
            @RequestHeader(value="Idempotency-Key",required=false) String requestId,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Lead-Command-Id", commands.enqueueImport(leadId,requestId));

        return "redirect:/dashboard"; // или JSON, если это API
    }
}
