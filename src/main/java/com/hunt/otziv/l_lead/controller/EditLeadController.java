package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.a_login.services.service.UserService;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.services.LeadService;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.security.Principal;
import java.util.Map;

@Controller
@Slf4j
public class EditLeadController {
    private final LeadService leadService;
    private final LeadValidation leadValidation;
    private final UserService userService;

    public EditLeadController(LeadService leadService, LeadValidation leadValidation, UserService userService) {
        this.leadService = leadService;
        this.leadValidation = leadValidation;
        this.userService = userService;
    }

    // ===============================  ДОБАВЛЕНИЕ НОВОГО ЛИДА - НАЧАЛО  ===============================

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
    // ===============================  ДОБАВЛЕНИЕ НОВОГО ЛИДА - КОНЕЦ  ===============================

    @GetMapping("lead/edit/{leadId}")
    public String editLead(@PathVariable final Long leadId, Model model){
        System.out.println(leadId);
//        model.put("route", "edit");
        model.addAttribute("editLeadDto", leadService.findById(leadId));
        model.addAttribute("operators", userService.getAllUsersByFio());

        return "lead/pages/edit_lead";
    }

    //Сохранение отредактированного лида
    @PostMapping("lead/edit/{leadId}")
    @PreAuthorize("hasRole('ADMIN')")
    public String editLead(@PathVariable final Long leadId,
                           @ModelAttribute("editLeadDto") @Valid LeadDTO leadDTO, BindingResult bindingResult
                           ){
        System.out.println(leadId);
        System.out.println(leadDTO.getId());
        System.out.println(leadDTO.getTelephoneLead());
        System.out.println(leadDTO.getUpdateStatus());
        System.out.println(leadDTO.getOperator());
        log.info("0. Валидация на повторный телефон");
        leadValidation.validate(leadDTO, bindingResult);
        log.info("1. Валидация данных");
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            return "lead/pages/edit_lead";
        }
        log.info("Начинаем обновлять Лида");
        leadService.updateProfile(leadDTO, leadId);
        log.info("Обновление лида прошло успешно");
        return "redirect:/lead";
    }

    //    =============================== СМЕНА СТАТУСОВ - НАЧАЛО =========================================

    // СМЕНА СТАТУСА НА "ОТПРАВЛЕНО" - НАЧАЛО

    // меняем статус с нового на отправленное - начало
    @PostMapping("lead/status_send/{leadId}")
    public String changeStatusLeadOnSend(Model model, @PathVariable final Long leadId,
                             Principal principal){
        log.info("вход в меняем статус с нового на отправленное");
        leadService.changeStatusLeadOnSend(leadId);
        log.info("статус успешно сменен с нового на отправленного" );
        return "redirect:/lead";
    }
    // меняем статус с нового на отправленное - конец

    // меняем статус с отправленное на напоминание - начало
    @PostMapping("lead/status_resend/{leadId}")
    public String changeStatusLeadOnReSend(Model model, @PathVariable final Long leadId,
                                         Principal principal){
        log.info("вход в меняем статус с отправленное на напоминание");
        leadService.changeStatusLeadOnReSend(leadId);
        log.info("статус успешно сменен с отправленное на напоминание" );
        return "redirect:/lead";
    }
    // меняем статус с отправленное на напоминание - конец

    // меняем статус с напоминание на К рассылке - начало
    @PostMapping("lead/status_archive/{leadId}")
    public String changeStatusLeadOnArchive(Model model, @PathVariable final Long leadId,
                                         Principal principal){
        log.info("вход в меняем статус с напоминание на К рассылке");
        leadService.changeStatusLeadOnArchive(leadId);
        log.info("статус успешно сменен с напоминание на К рассылке" );
        return "redirect:/lead";
    }
    // меняем статус с напоминание на К рассылке - конец

    // меняем статус с К рассылке на В работе - начало
    @PostMapping("lead/status_in_work/{leadId}")
    public String changeStatusLeadOnInWork(Model model, @PathVariable final Long leadId,
                                         Principal principal){
        log.info("вход в меняем статус с К рассылке на В работе");
        leadService.changeStatusLeadOnInWork(leadId);
        log.info("статус успешно сменен К рассылке на В работе" );
        return "redirect:/lead";
    }
    // меняем статус с К рассылке на В работе - конец

    // меняем статус с любого на Новый - начало
    @PostMapping("lead/status_lead_new/{leadId}")
    public String changeStatusLeadOnNew(Model model, @PathVariable final Long leadId,
                                         Principal principal){
        log.info("вход в меняем статус с любого на Новый ");
        leadService.changeStatusLeadOnNew(leadId);
        log.info("статус успешно сменен с любого на Новый " );
        return "redirect:/lead";
    }
    // меняем статус с любого на Новый - конец

    //    =============================== СМЕНА СТАТУСОВ - КОНЕЦ =========================================








}
