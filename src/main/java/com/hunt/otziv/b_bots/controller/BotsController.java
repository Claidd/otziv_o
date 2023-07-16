package com.hunt.otziv.b_bots.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/bots")
public class BotsController {

    //Открываем главную страницу
    @GetMapping
    public String bots(){
        return "bots";
    }

}
