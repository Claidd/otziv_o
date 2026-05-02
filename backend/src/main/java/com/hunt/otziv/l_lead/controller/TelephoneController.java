package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.TelephoneDTO;
import com.hunt.otziv.l_lead.services.serv.TelephoneService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/phone")
@RequiredArgsConstructor
public class TelephoneController {

    private final TelephoneService telephoneService;
    private final OperatorService operatorService;

    @GetMapping("")
    public String allPhones(Model model){
        model.addAttribute("all_phones", telephoneService.getAllTelephones());
        return "telephone/phone_list";
    }

    @GetMapping("/{phoneId}/edit")
    public String editPhone(Model model, @PathVariable final Long phoneId){
        model.addAttribute("phone", telephoneService.getTelephoneDTOById(phoneId));
        model.addAttribute("operators", operatorService.getAllOperators()); // <-- Добавить список
        return "telephone/phone_edit";
    }

    // CONTROLLER
    @PostMapping("/{phoneId}/edit")
    public String updatePhone(@PathVariable Long phoneId,
                              @ModelAttribute("phone") TelephoneDTO dto) {
        telephoneService.updatePhone(phoneId, dto);
        return "redirect:/phone"; // или показать success-страницу, если нужно
    }

    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("phone", telephoneService.createEmptyDTO());
        model.addAttribute("operators", operatorService.getAllOperators());
        return "telephone/phone_add";
    }

    @PostMapping("/add")
    public String addPhone(@ModelAttribute("phone") TelephoneDTO dto) {
        telephoneService.createTelephone(dto);
        return "redirect:/phone";
    }

    @PostMapping("/{phoneId}/delete")
    public String deletePhone(@PathVariable Long phoneId) {
        telephoneService.deletePhone(phoneId);
        return "redirect:/phone"; // или показать success-страницу, если нужно
    }
}
