package com.hunt.otziv.a_login.controller;

import com.hunt.otziv.a_login.dto.UserDTO;
import com.hunt.otziv.a_login.services.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.security.Principal;

@Controller
@Slf4j
@RequestMapping("/register")
public class RegistrationController {

    private final UserService userService;

    public RegistrationController(UserService userService) {
        this.userService = userService;
    }

    @ModelAttribute("newUser")
    public UserDTO userDTO(){
        return new UserDTO();
    }

    @GetMapping
    public String registration(Model model){
//        model.addAttribute("newUser", userService.getUserByPrincipal(principal));
//        model.addAttribute("newUser", new UserDTO());
        return "1.Login_and_Register/register";
    }

    @PostMapping
    public String createUser(Model model, @ModelAttribute("newUser") @Valid UserDTO userDto, BindingResult bindingResult){
        log.info("1. Валидация данных");
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
//            System.out.println(bindingResult.getAllErrors().toString());
//            model.addAttribute("newUser", userDto);
            return "1.Login_and_Register/register";
        }

        /*Проверяем на совпадение паролей*/
        if (!userDto.getPassword().equals(userDto.getMatchingPassword())){
            bindingResult.rejectValue("password", "", "Пароли не совпадают");
            model.addAttribute("newUser", userDto);
            return "1.Login_and_Register/register";
        }

        log.info("2.Передаем дто в сервис");
        if (!userService.create(userDto)) {
            model.addAttribute("newUser", userDto);
            return "1.Login_and_Register/register";
        }
        log.info("6. Возвращаем вью");
        return "redirect:/login";
    }
}
