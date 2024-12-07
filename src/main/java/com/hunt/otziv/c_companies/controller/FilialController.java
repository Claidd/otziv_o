package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.sevices.CityService;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.services.FilialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/filial")
public class FilialController {

    private final FilialService filialService;
    private final CityService cityService;

    @GetMapping("/edit/{filialId}")
    String editCompanyDeleteFilial(@PathVariable Long filialId, Model model){ // редакция филиала
        model.addAttribute("editFilialDTO", filialService.getFilialByIdToDTO(filialId));
        List<CityDTO> citiesList = cityService.getAllCities().stream().sorted(Comparator.comparing(CityDTO::getCityTitle)).toList();
        model.addAttribute("cities", citiesList);
        return "companies/filial_edit";
    } // редакция филиала

    @PostMapping("/edit/{filialId}")
    String editCompany(@ModelAttribute("editFilialDTO") FilialDTO filialDTO, Model model, RedirectAttributes rm){ // Обновление филиала
        log.info("1. Начинаем обновлять данные филиала");
        filialService.updateFilial(filialDTO);
        log.info("5. Обновление филиала прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/filial/edit/{filialId}";
    } // Обновление филиала
}
