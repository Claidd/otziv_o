package com.hunt.otziv.z_zp.controller;

import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/zp")
public class ZpController {

    private final ZpService zpService;
    @GetMapping
    private String zpList(Model model){ // Страница с ЗП
        model.addAttribute("zpList", zpService.getAllZpDTO());
        return "1.Login_and_Register/zp_list";
    } // Страница с ЗП
}
