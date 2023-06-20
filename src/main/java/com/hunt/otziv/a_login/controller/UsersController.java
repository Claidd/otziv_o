package com.hunt.otziv.a_login.controller;

import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.a_login.services.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
@RequestMapping("/allUsers")
public class UsersController {
    private final UserService userService;

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


    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('ADMIN')")
    public String editUser(@PathVariable(value = "id")  long id, Principal principal, @ModelAttribute("all_users") RegistrationUserDTO userDto){
//        model.addAttribute("editUser", userService.)
        System.out.println(principal.getName());
        System.out.println(userDto.getUsername());
        return "1.Login_and_Register/login";
    }
}
