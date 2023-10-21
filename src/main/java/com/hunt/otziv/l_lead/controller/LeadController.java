package com.hunt.otziv.l_lead.controller;


import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.l_lead.services.PromoTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.security.Principal;
import java.util.Map;

@Controller
@Slf4j
@RequestMapping("/lead")
public class LeadController {

    private final LeadService leadService;
    private final PromoTextService promoTextService;

    public LeadController(LeadService leadService, PromoTextService promoTextService) {
        this.leadService = leadService;
        this.promoTextService = promoTextService;
    }

    @GetMapping()
    public ModelAndView lead(final Map<String, Object> model, @RequestParam(defaultValue = "") String keyword, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        int pageSize = 10; // желаемый размер страницы
        Page<LeadDTO> leadsNew = leadService.getAllLeads(LeadStatus.NEW.title, keyword, principal, pageNumber, pageSize);
        Page<LeadDTO> leadsSend = leadService.getAllLeadsToDateReSend(LeadStatus.SEND.title, keyword, principal, pageNumber, pageSize);
        Page<LeadDTO> leadsReSend = leadService.getAllLeadsToDateReSend(LeadStatus.RESEND.title, keyword, principal, pageNumber, pageSize);
        Page<LeadDTO> leadsArchive = leadService.getAllLeadsToDateReSend(LeadStatus.ARCHIVE.title, keyword, principal, pageNumber, pageSize);
        Page<LeadDTO> leadsInWork = leadService.getAllLeads(LeadStatus.INWORK.title, keyword, principal, pageNumber, pageSize);
        Page<LeadDTO> leadsAll = leadService.getAllLeadsNoStatus(keyword, principal, pageNumber, pageSize);


        model.put("promoTexts", promoTextService.getAllPromoTexts());
        log.info("загрузили промо тексты");
        model.put("leadListNew", leadsNew);
        log.info("загрузили НОВЫЕ компании");
        model.put("leadListSend", leadsSend);
        log.info("загрузили ОТПРАВЛЕННЫЕ компании");
        model.put("leadListReSend", leadsReSend);
        log.info("загрузили НАПОМНЕННЫЕ компании");
        model.put("leadListArchive", leadsArchive);
        log.info("загрузили АРХИВ компании");
        model.put("leadListInWork", leadsInWork);
        log.info("загрузили В РАБОТЕ компании");
        model.put("leadListALL", leadsAll);
        log.info("загрузили ВСЕ компании");

        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf("Лид контроллер: %.4f сек%n", timeElapsed);

        return new ModelAndView("lead/layouts/lead", model);
    }


}
