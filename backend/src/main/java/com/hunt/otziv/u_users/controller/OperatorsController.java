package com.hunt.otziv.u_users.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/operator")
public class OperatorsController {

    @GetMapping("/delete/{userId}/{operatorId}")
    public String deleteOperatorByUser(@PathVariable(name="userId") Long userId, @PathVariable(name="operatorId") Long operatorId){
//        System.out.println(userId);
//        System.out.println(operatorId);
        return "redirect:/allUsers";
    }

}
