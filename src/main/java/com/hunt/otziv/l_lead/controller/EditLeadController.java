package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.Map;

@Controller
@Slf4j
public class EditLeadController {
    private final LeadService leadService;
    private final LeadValidation leadValidation;
    private final MarketologService marketologService;
    private final OperatorService operatorService;
    private final ManagerService managerService;

    public EditLeadController(LeadService leadService, LeadValidation leadValidation, MarketologService marketologService, OperatorService operatorService, ManagerService managerService) {
        this.leadService = leadService;
        this.leadValidation = leadValidation;
        this.marketologService = marketologService;
        this.operatorService = operatorService;
        this.managerService = managerService;
    }

    // ===============================  ДОБАВЛЕНИЕ НОВОГО ЛИДА - НАЧАЛО  ===============================

    //Добавляем на страницу добавления нового лида модель, а именно дто, он будет автоматически добавляться ко всем гет запросам
    @ModelAttribute("newLead")
    public LeadDTO leadDTO(){
        return new LeadDTO();
    }

    //Страница создания Лида
    @GetMapping("lead/new_lead")
    public ModelAndView createLead(final Map<String, Object> model){
        model.put("route", "create");
        return new ModelAndView("lead/layouts/new_lead", model);
    }

    //Пост запрос на несение нового лида, его валидация и сохранение в БД.
    @PostMapping("lead/new_lead")
    public String createLead(Model model, RedirectAttributes rm, @ModelAttribute("newLead") @Valid LeadDTO leadDTO, BindingResult bindingResult,
                             Principal principal){
        log.info("0. Валидация на повторный мейл");
        leadValidation.validate(leadDTO, bindingResult);
        log.info("1. Валидация данных");
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            model.addAttribute("newLead", leadDTO);
            return "lead/layouts/new_lead";
        }

        log.info("2.Передаем дто в сервис");
        if (leadService.save(leadDTO, principal.getName()) == null) {
            model.addAttribute("newLead", leadDTO);

        } else {
            log.info("6. Возвращаем вью");
            rm.addFlashAttribute("saveSuccess", "true");
        }
        return "redirect:/lead/new_lead";
    }
    // ===============================  ДОБАВЛЕНИЕ НОВОГО ЛИДА - КОНЕЦ  ===============================

    //Редактор лида
    @GetMapping("lead/edit/{leadId}")
    public String editLead(@PathVariable final Long leadId, Model model){
        log.info("🟢 GET-запрос на редактирование лида с ID={}", leadId);
        model.addAttribute("editLeadDto", leadService.findById(leadId));
        model.addAttribute("operators", operatorService.getAllOperators());
        model.addAttribute("managers", managerService.getAllManagers());
        model.addAttribute("marketologs", marketologService.getAllMarketologs());
        return "lead/pages/edit_lead";
    }

    //Сохранение отредактированного лида
    @PostMapping("lead/edit/{leadId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OWNER')")
    public String editLead(@PathVariable final Long leadId,
                           @ModelAttribute("editLeadDto")LeadDTO leadDTO){
        log.info("0. Валидация на повторный телефон");
//        leadValidation.validate(leadDTO, bindingResult);
        log.info("1. Валидация данных");
        /*Проверяем на ошибки*/
//        if (bindingResult.hasErrors()) {
//            log.info("1.1 Вошли в ошибку");
//            return "lead/pages/edit_lead";
//        }
        log.info("Начинаем обновлять Лида");
        leadService.updateProfile(leadDTO, leadId);
        log.info("Обновление лида прошло успешно");
        return "redirect:/lead";
    }

    //    =============================== СМЕНА СТАТУСОВ - НАЧАЛО =========================================

    // СМЕНА СТАТУСА НА "ОТПРАВЛЕНО" - НАЧАЛО

    // меняем статус с нового на отправленное - начало
    @PostMapping("lead/status_send/{leadId}")
    public String changeStatusLeadOnSend(Model model,RedirectAttributes rm, @PathVariable final Long leadId,
                             Principal principal){
        log.info("вход в меняем статус с нового на отправленное");
        leadService.changeStatusLeadOnSend(leadId);
        log.info("статус успешно сменен с нового на отправленного" );
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/lead";
    }
    // меняем статус с нового на отправленное - конец

    // меняем статус с нового на отправленное - начало
    @PostMapping("lead/status_to_work/{leadId}")
    public String changeStatusLeadToWork(Model model, RedirectAttributes rm, @PathVariable final Long leadId,
                                         @RequestParam(required = false) String commentsLead, Principal principal){
        log.info("вход в меняем статус с нового на В Работу");
        leadService.changeStatusLeadToWork(leadId, commentsLead);
        log.info("статус успешно сменен с нового на В Работу" );
        rm.addFlashAttribute("saveSuccess", "true");
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
