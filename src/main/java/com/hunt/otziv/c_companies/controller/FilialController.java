package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/filial")
public class FilialController {

    private final FilialService filialService;

    @GetMapping("/edit/{filialId}")
    String editCompanyDeleteFilial(@ModelAttribute ("OK") String ok, @PathVariable Long filialId, Model model){
        model.addAttribute("editFilialDTO", filialService.getFilialByIdToDTO(filialId));
        return "companies/filial_edit";
    }

    @PostMapping("/edit/{filialId}")
    String editCompany(@ModelAttribute("editFilialDTO") FilialDTO filialDTO, Model model, RedirectAttributes rm){
        log.info("1. Начинаем обновлять данные филиала");
        filialService.updateFilial(filialDTO);
        log.info("5. Обновление филиала прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/filial/edit/{filialId}";
    }
}
