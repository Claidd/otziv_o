package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.dto.*;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.services.RoleService;
import com.hunt.otziv.u_users.services.service.*;
import com.hunt.otziv.u_users.utils.UserValidation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    private final MarketologService marketologService;
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
    @GetMapping("/{userId}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editUser(@PathVariable(value = "userId")  long userId, Model model){
        model.addAttribute("editUserDto", userService.findById(userId));
        model.addAttribute("roles", roleService.getAllRoles());
        model.addAttribute("AllOperators", operatorService.getAllOperators());
        model.addAttribute("AllManagers", managerService.getAllManagers());
        model.addAttribute("AllWorkers", workerService.getAllWorkers());
        model.addAttribute("AllMarketologs", marketologService.getAllMarketologs());
        model.addAttribute("operatorDTO", new OperatorDTO());
        model.addAttribute("managerDTO", new ManagerDTO());
        model.addAttribute("workerDTO", new WorkerDTO());
        model.addAttribute("marketologDTO", new MarketologDTO());
        return "1.Login_and_Register/editUser";
    }

    //Сохранение отредактированного пользователя
    @PostMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editUser(@PathVariable(value = "id")  long id, Model model,
                           @ModelAttribute("editUserDto")  RegistrationUserDTO userDto,
                           @RequestParam String role,
                           @RequestParam("imageFile") MultipartFile imageFile,
                           @ModelAttribute("operatorDTO") OperatorDTO operatorDTO,
                           @ModelAttribute("managerDTO") ManagerDTO managerDTO,
                           @ModelAttribute("workerDTO") WorkerDTO workerDTO,
                           @ModelAttribute("marketologDTO") MarketologDTO marketologDTO) throws IOException {
        log.info("0. Валидация на повторный мейл");
        System.out.println("================================================================");
        System.out.println(imageFile);
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
        log.info("Начинаем обновлять юзера");
        userService.updateProfile(userDto, role, operatorDTO, managerDTO, workerDTO, marketologDTO, imageFile);
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
    @GetMapping("/delete/marketolog/{username}/{marketologId}")
    public String deleteMarketologByUser(@PathVariable(name="username") String username, @PathVariable(name="marketologId") Long marketologId){
        System.out.println(username);
        System.out.println(marketologId);
        log.info("Входим в удаление маркетолога");
        userService.deleteMarketolog(username, marketologId);
        log.info("Удаление маркетолога прошло успешно");
        return "redirect:/allUsers";
    }


}
