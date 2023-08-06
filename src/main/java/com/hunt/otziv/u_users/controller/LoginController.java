package com.hunt.otziv.u_users.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/login")
public class LoginController {

    //Открываем страницу логирования
    @GetMapping
    public String login(){
        return "1.Login_and_Register/login";
    }

//    @RequestMapping("/login-error") // чтобы пользователь не попал при ошибке на 404 страницу
//    public String loginError(Model model) {
//        model.addAttribute("loginError", true);
//        return "1.Login_and_Register/login";
//    }
}
