package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.services.LeadService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;

import java.security.Principal;
import java.util.Map;

@Controller
@Slf4j
public class EditLeadController {
    private final LeadService leadService;
    private final LeadValidation leadValidation;

    public EditLeadController(LeadService leadService, LeadValidation leadValidation) {
        this.leadService = leadService;
        this.leadValidation = leadValidation;
    }


    //Добавляем на страницу добавления нового лида модель, а именно дто, он будет автоматически добавляться ко всем гет запросам
    @ModelAttribute("newLead")
    public LeadDTO leadDTO(){
        return new LeadDTO();
    }

    @GetMapping("lead/new_lead")
    public ModelAndView createLead(final Map<String, Object> model){
        model.put("route", "create");
        return new ModelAndView("lead/layouts/new_lead", model);
    }

    @GetMapping("lead/edit/{leadId}")
    public ModelAndView editLead(@PathVariable final String leadId, final Map<String, Object> model){
        model.put("route", "edit");
        return new ModelAndView("lead/layouts/edit", model);
    }


    //Открываем главную страницу
//    @GetMapping("/new_lead")
//    public String newLead(Model model){
//        return "lead/new_lead.html";
//    }


    //Пост запрос на несение нового лида, его валидация и сохранение в БД.
    @PostMapping("lead/new_lead")
    public String createLead(Model model, @ModelAttribute("newLead") @Valid LeadDTO leadDTO, BindingResult bindingResult,
                             Principal principal){
        log.info("0. Валидация на повторный мейл");
        leadValidation.validate(leadDTO, bindingResult);
        log.info("1. Валидация данных");
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            return "/lead/new_lead";
        }

        log.info("2.Передаем дто в сервис");
        if (leadService.save(leadDTO, principal.getName()) == null) {
            model.addAttribute("newLead", leadDTO);
            return "/lead/new_lead";
        }
        log.info("6. Возвращаем вью");
        return "redirect:/lead";
    }
}
