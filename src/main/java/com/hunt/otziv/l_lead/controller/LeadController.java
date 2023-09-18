package com.hunt.otziv.l_lead.controller;


import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.l_lead.services.PromoTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
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
    public ModelAndView lead(final Map<String, Object> model, @RequestParam(defaultValue = "") String keyword) {
//        model.put("route", "lead");
        model.put("promoTexts", promoTextService.getAllPromoTexts());
        log.info("загрузили промо тексты");
        model.put("leadListNew", leadService.getAllLeads(LeadStatus.NEW.title, keyword));
        log.info("загрузили НОВЫЕ компании");
        model.put("leadListSend", leadService.getAllLeadsToDateReSend(LeadStatus.SEND.title, keyword));
        log.info("загрузили ОТПРАВЛЕННЫЕ компании");
        model.put("leadListReSend", leadService.getAllLeadsToDateReSend(LeadStatus.RESEND.title, keyword));
        log.info("загрузили НАПОМНЕННЫЕ компании");
        model.put("leadListArchive", leadService.getAllLeadsToDateReSend(LeadStatus.ARCHIVE.title, keyword));
        log.info("загрузили АРХИВ компании");
        model.put("leadListInWork", leadService.getAllLeads(LeadStatus.INWORK.title, keyword));
        log.info("загрузили В РАБОТЕ компании");
        model.put("leadListALL", leadService.getAllLeadsNoStatus(keyword));
        System.out.println(leadService.getAllLeadsNoStatus(keyword));
        log.info("загрузили ВСЕ компании");
        return new ModelAndView("lead/layouts/lead", model);
    }

}
