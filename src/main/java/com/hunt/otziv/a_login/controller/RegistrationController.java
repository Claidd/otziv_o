package com.hunt.otziv.a_login.controller;

import com.hunt.otziv.a_login.dto.RegistrationUserDTO;


import com.hunt.otziv.a_login.services.UserServiceImpl;
import com.hunt.otziv.a_login.utils.UserValidation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@Slf4j
@RequestMapping("/register")
public class RegistrationController {

    private final UserServiceImpl userService;
    private final UserValidation userValidation;

    public RegistrationController(UserServiceImpl userService, UserValidation userValidation) {
        this.userService = userService;
        this.userValidation = userValidation;
    }

    @ModelAttribute("newUser")
    public RegistrationUserDTO userDTO(){
        return new RegistrationUserDTO();
    }

    @GetMapping
    public String registration(Model model){
//        model.addAttribute("newUser", userService.getUserByPrincipal(principal));
//        model.addAttribute("newUser", new UserDTO());
        return "1.Login_and_Register/register";
    }

    @PostMapping
    public String createUser(Model model, @ModelAttribute("newUser") @Valid RegistrationUserDTO userDto, BindingResult bindingResult){
        log.info("0. Валидация на повторный мейл");
        userValidation.validate(userDto, bindingResult);
        log.info("1. Валидация данных");
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            return "1.Login_and_Register/register";
        }

        log.info("2.Передаем дто в сервис");
        if (userService.save(userDto) == null) {
            model.addAttribute("newUser", userDto);
            return "1.Login_and_Register/register";
        }
        log.info("6. Возвращаем вью");
        return "redirect:/login";
    }
}
