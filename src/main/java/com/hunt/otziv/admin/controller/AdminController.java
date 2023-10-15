package com.hunt.otziv.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminController {

    //Открываем главную страницу

    @GetMapping()
    String adminPanel(Model model){
        return "admin/admin_panel";
    }

}
