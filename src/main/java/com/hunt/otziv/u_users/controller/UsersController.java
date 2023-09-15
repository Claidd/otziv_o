package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.dto.*;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.services.RoleService;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.u_users.utils.UserValidation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/allUsers")
public class UsersController {
    private final UserService userService;
    private final RoleService roleService;
    private final OperatorService operatorService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final UserValidation userValidation;

    //Страница со всеми пользователями
    @GetMapping
    public String getAllUsers(){
        return "1.Login_and_Register/all_users";
    }

    //Модель со списком все пользователей
    @ModelAttribute(name = "all_users")
    public List<RegistrationUserDTO> allUsersList(){
        List<RegistrationUserDTO> userDTO = userService.getAllUsers();
//        for (RegistrationUserDTO user: userDTO) {
////            for (Operator operator: user.getOperators()) {
////                System.out.println(operator.getUser() + " / ");
////            }
//            System.out.println(user.getOperators() + " " + user.getManagers() + " " + user.getWorkers());
//        }
        return userDTO;
    }

    //Открытие страницы редактирования пользователя
    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editUser(@PathVariable(value = "id")  long id, Model model){
        model.addAttribute("editUserDto", userService.findById(id));
        model.addAttribute("roles", roleService.getAllRoles());
        model.addAttribute("AllOperators", operatorService.getAllOperators());
        model.addAttribute("AllManagers", managerService.getAllManagers());
        model.addAttribute("AllWorkers", workerService.getAllWorkers());
        model.addAttribute("operatorDTO", new OperatorDTO());
        model.addAttribute("managerDTO", new ManagerDTO());
        model.addAttribute("workerDTO", new WorkerDTO());
//        for (Operator operator: operatorService.getAllOperators()) {
//            System.out.println(operator);
//        }
        RegistrationUserDTO userDTO = userService.findById(id);
        System.out.println(userDTO.getManager());
        return "1.Login_and_Register/editUser";
    }

    //Сохранение отредактированного пользователя
    @PostMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editUser(@PathVariable(value = "id")  long id, Model model,
                           @ModelAttribute("editUserDto")  RegistrationUserDTO userDto,
                           @RequestParam String role,
                           @ModelAttribute("operatorDTO") OperatorDTO operatorDTO,
                           @ModelAttribute("managerDTO") ManagerDTO managerDTO,
                               @ModelAttribute("workerDTO") WorkerDTO workerDTO){
        log.info("0. Валидация на повторный мейл");
        System.out.println(operatorDTO.getOperatorId());
        System.out.println(managerDTO.getManagerId());
        System.out.println(workerDTO.getWorkerId());
        System.out.println("================================================================");
        System.out.println(userDto.getManager());
        System.out.println("================================================================");
//        userValidation.validate(userDto, bindingResult);
//        log.info("1. Валидация данных");
//        /*Проверяем на ошибки*/
//        if (bindingResult.hasErrors()) {
//            log.info("1.1 Вошли в ошибку");
//            return "1.Login_and_Register/editUser";
//        }
//        for (Operator operator:userDto.getOperators()) {
//            System.out.println(operator);
//        }


        System.out.println(userDto.getOperators());
        System.out.println(userDto.getRoles());
        System.out.println(role);
        log.info("Начинаем обновлять юзера");
        userService.updateProfile(userDto, role, operatorDTO, managerDTO, workerDTO);
        log.info("Обновление юзера прошло успешно");
        return "redirect:/allUsers";
    }

    @GetMapping("/delete/operator/{username}/{operatorId}")
    public String deleteOperatorByUser(@PathVariable(name="username") String username, @PathVariable(name="operatorId") Long operatorId){
        System.out.println(username);
        System.out.println(operatorId);
        userService.deleteOperator(username, operatorId);
        log.info("Удаление оператора прошло успешно");
        return "redirect:/allUsers";
    }

    @GetMapping("/delete/manager/{username}/{managerId}")
    public String deleteManagerByUser(@PathVariable(name="username") String username, @PathVariable(name="managerId") Long managerId){
        System.out.println(username);
        System.out.println(managerId);
        userService.deleteManager(username, managerId);
        log.info("Удаление менеджера прошло успешно");
        return "redirect:/allUsers";
    }

    @GetMapping("/delete/worker/{username}/{workerId}")
    public String deleteWorkerByUser(@PathVariable(name="username") String username, @PathVariable(name="workerId") Long workerId){
        System.out.println(username);
        System.out.println(workerId);
        userService.deleteWorker(username, workerId);
        log.info("Удаление работника прошло успешно");
        return "redirect:/allUsers";
    }

}
