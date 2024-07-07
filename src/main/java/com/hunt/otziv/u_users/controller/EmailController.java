package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.config.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailController {

    @Autowired
    private EmailService emailService;

    @GetMapping("/sendEmail")
    public String sendEmail() {
        String to = "2.12nps@mail.ru";
        String subject = "Привет";
        String text = "Текст сообщения из приложения 2";
        emailService.sendSimpleEmail(to, subject, text);
        return "Email sent successfully!";
    }
}
