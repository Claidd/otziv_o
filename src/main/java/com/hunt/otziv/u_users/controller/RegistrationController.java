package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.dto.RegistrationUserDTO;


import com.hunt.otziv.u_users.services.UserServiceImpl;
import com.hunt.otziv.u_users.utils.UserValidation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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

    //Открываем страницу регистрации
    @GetMapping
    public String registration(Model model){
        return "1.Login_and_Register/register";
    }


    //Добавляем на страницу регистрации модель, а именно дто, он будет автоматически добавляться ко всем гет запросам
    @ModelAttribute("newUser")
    public RegistrationUserDTO userDTO(){
        return new RegistrationUserDTO();
    }

    //Пост запрос на регистрацию нового пользователя, его валидация и сохранение в БД.
    @PostMapping
    public String createUser(Model model, @ModelAttribute("newUser") @Valid RegistrationUserDTO userDto, BindingResult bindingResult, @RequestParam("file") MultipartFile file) throws IOException {
        log.info("0. Валидация на повторный мейл");
        userValidation.validate(userDto, bindingResult);
        log.info("1. Валидация данных");
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            return "1.Login_and_Register/register";
        }
        log.info("2.Передаем дто в сервис");
        if (userService.save(userDto, file) == null) {
            model.addAttribute("newUser", userDto);
            return "1.Login_and_Register/register";
        }
        log.info("6. Возвращаем вью");
        return "redirect:/login";
    }   //Пост запрос на регистрацию нового пользователя, его валидация и сохранение в БД.


}
