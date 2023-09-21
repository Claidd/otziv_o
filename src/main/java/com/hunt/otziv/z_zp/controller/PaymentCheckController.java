package com.hunt.otziv.z_zp.controller;

import com.hunt.otziv.z_zp.services.PaymentCheckService;
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
@RequestMapping("/payment_check")
public class PaymentCheckController {

    private final PaymentCheckService paymentCheckService;
    @GetMapping
    private String checkList(Model model){
        model.addAttribute("checkList", paymentCheckService.getAllCheckDTO());
        return "1.Login_and_Register/payment_check";
    }
}
