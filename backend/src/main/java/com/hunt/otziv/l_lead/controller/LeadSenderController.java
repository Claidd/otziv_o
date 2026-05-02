package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.services.serv.LeadTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadSenderController {

    private final LeadService leadService; // ваш сервис, который умеет брать лиды из БД
    private final LeadTransferService leadTransferService;

    /**
     * Отправка лида с локальной машины на VPS по кнопке
     */
    @PostMapping("/sendToServer")
    public String sendLeadToServer(@RequestParam Long leadId) {
        leadTransferService.sendLeadToServer(leadId);

        return "redirect:/dashboard"; // или JSON, если это API
    }
}
