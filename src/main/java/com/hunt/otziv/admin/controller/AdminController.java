package com.hunt.otziv.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminController {


    @GetMapping() //Открываем главную страницу
    String adminPanel(Model model){
        return "admin/admin_panel";
    }

//    LocalDateTime startTime = LocalDateTime.now();
//
//    LocalDateTime stopTime = LocalDateTime.now();
//    Duration duration = Duration.between(startTime, stopTime);
//    System.out.println(duration);
}
