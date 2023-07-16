package com.hunt.otziv.a_login.controller;

import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.Role;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.a_login.services.RoleService;
import com.hunt.otziv.a_login.services.service.UserService;
import com.hunt.otziv.a_login.utils.UserValidation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/allUsers")
public class UsersController {
    private final UserService userService;
    private final RoleService roleService;
    private final UserValidation userValidation;

    //Страница со всеми пользователями
    @GetMapping
    public String getAllUsers(){
        return "1.Login_and_Register/all_users";
    }

    //Модель со списком все пользователей
    @ModelAttribute(name = "all_users")
    public List<RegistrationUserDTO> allUsersList(){
        return userService.getAllUsers();
    }

    //Открытие страницы редактирования пользователя
    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editUser(@PathVariable(value = "id")  long id, Model model){
        model.addAttribute("editUserDto", userService.findById(id));
        model.addAttribute("roles", roleService.getAllRoles());
        return "1.Login_and_Register/editUser";
    }

    //Сохранение отредактированного пользователя
    @PostMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editUser(@PathVariable(value = "id")  long id, Model model,
                           @ModelAttribute("editUserDto")  RegistrationUserDTO userDto,
                           @RequestParam String role){
        log.info("0. Валидация на повторный мейл");
//        userValidation.validate(userDto, bindingResult);
//        log.info("1. Валидация данных");
//        /*Проверяем на ошибки*/
//        if (bindingResult.hasErrors()) {
//            log.info("1.1 Вошли в ошибку");
//            return "1.Login_and_Register/editUser";
//        }
        System.out.println(userDto.getRoles());
        System.out.println(role);
        log.info("Начинаем обновлять юзера");
        userService.updateProfile(userDto, role);
        log.info("Обновление юзера прошло успешно");
        return "redirect:/allUsers";
    }
}
