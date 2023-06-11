package com.hunt.otziv.a_login.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/login")
public class LoginController {



    @GetMapping
    public String login(){
        return "1.Login_and_Register/login";
    }
}
