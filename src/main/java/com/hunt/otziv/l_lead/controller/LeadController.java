package com.hunt.otziv.l_lead.controller;


import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.l_lead.services.PromoTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public ModelAndView lead(final Map<String, Object> model){
//        model.put("route", "lead");
        model.put("promoTexts", promoTextService.getAllPromoTexts());
        model.put("leadListNew", leadService.getAllLeads(LeadStatus.NEW.title));
        model.put("leadListSend", leadService.getAllLeads(LeadStatus.SEND.title));
        model.put("leadListReSend", leadService.getAllLeads(LeadStatus.RESEND.title));
        model.put("leadListArchive", leadService.getAllLeads(LeadStatus.ARCHIVE.title));
        model.put("leadListInWork", leadService.getAllLeads(LeadStatus.INWORK.title));
        return new ModelAndView("lead/layouts/lead", model);
    }








}
