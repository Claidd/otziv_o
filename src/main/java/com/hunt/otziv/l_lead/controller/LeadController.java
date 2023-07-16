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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.security.Principal;

@Controller
@Slf4j
@RequestMapping("/lead")
public class LeadController {

    private final LeadService leadService;
    private final LeadValidation leadValidation;

    public LeadController(LeadService leadService, LeadValidation leadValidation) {
        this.leadService = leadService;
        this.leadValidation = leadValidation;
    }

    //Добавляем на страницу добавления нового лида модель, а именно дто, он будет автоматически добавляться ко всем гет запросам
    @ModelAttribute("newLead")
    public LeadDTO leadDTO(){
        return new LeadDTO();
    }


    //Открываем главную страницу
    @GetMapping
    public String newLead(Model model){
        return "lead/new_lead.html";
    }


    //Пост запрос на несение нового лида, его валидация и сохранение в БД.
    @PostMapping("/new_lead")
    @PreAuthorize("hasRole('ADMIN')")
    public String createLead(Model model, @ModelAttribute("newLead") @Valid LeadDTO leadDTO, BindingResult bindingResult,
                             Principal principal){
        log.info("0. Валидация на повторный мейл");
        leadValidation.validate(leadDTO, bindingResult);
        log.info("1. Валидация данных");
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            return "lead/new_lead.html";
        }

        log.info("2.Передаем дто в сервис");
        if (leadService.save(leadDTO, principal.getName()) == null) {
            model.addAttribute("newLead", leadDTO);
            return "lead/new_lead.html";
        }
        log.info("6. Возвращаем вью");
        return "redirect:/lead";
    }


}
