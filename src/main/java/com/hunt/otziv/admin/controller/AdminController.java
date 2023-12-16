package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final PersonalService personalService;
    private final UserService userService;
    private final ManagerService managerService;


    @GetMapping()
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView lK(final Map<String, Object> model,  Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        model.put("route", "user_info");
        model.put("workerZp", personalService.getWorkerReviews(principal.getName()));
        model.put("user", personalService.getUserLK(principal));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @PostMapping()
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView lKPost(final Map<String, Object> model,  Principal principal, @RequestParam(defaultValue = "0") int pageNumber, @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();
        model.put("route", "user_info");
        model.put("workerZp", personalService.getWorkerReviews2(principal.getName(), date));
        model.put("user", personalService.getUserLK(principal));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @GetMapping("/personal")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_MANAGER')")
    public ModelAndView personal(final Map<String, Object> model, @RequestParam(defaultValue = "") String keyword, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);
        if ("ROLE_ADMIN".equals(userRole)) {
            model.put("route", "personal");
            model.put("user", personalService.getUserLK(principal));
            model.put("managers", personalService.getManagers());
            model.put("marketologs", personalService.getMarketologs());
            model.put("workers", personalService.gerWorkers());
            model.put("operators", personalService.gerOperators());
            checkTimeMethod("Время выполнения AdminController/admin/personal для Админа: ",startTime);
            return new ModelAndView("admin/layouts/personal", model);
        }

        if ("ROLE_MANAGER".equals(userRole)) {
            model.put("route", "personal");
            model.put("user", personalService.getUserLK(principal));
            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
//            model.put("managers", personalService.getManagersToManager(manager));
            model.put("marketologs", personalService.getMarketologsToManager(manager));
            model.put("workers", personalService.gerWorkersToManager(manager));
            model.put("operators", personalService.gerOperatorsToManager(manager));
            checkTimeMethod("Время выполнения AdminController/admin/personal для Менеджера: ",startTime);
            return new ModelAndView("admin/layouts/personal", model);
        }
        else return new ModelAndView("admin/layouts/personal", model);
    }

    @GetMapping("/score")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView score(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);
        if ("ROLE_ADMIN".equals(userRole)) {
            model.put("user", personalService.getUserLK(principal));
            model.put("route", "score");
            model.put("managers", personalService.getManagersAndCount().stream().sorted(Comparator.comparing(ManagersListDTO:: getSum1Month).reversed()));
            model.put("marketologs", personalService.getMarketologsAndCount().stream().sorted(Comparator.comparing(MarketologsListDTO:: getSum1Month).reversed()));
            model.put("workers", personalService.gerWorkersToAndCount().stream().sorted(Comparator.comparing(WorkersListDTO:: getSum1Month).reversed()));
            model.put("operators", personalService.gerOperatorsAndCount().stream().sorted(Comparator.comparing(OperatorsListDTO:: getSum1Month).reversed()));
            checkTimeMethod("Время выполнения AdminController/admin/score для ADMIN: ",startTime);
            return new ModelAndView("admin/layouts/score", model);
        }
        else {
            model.put("route", "score");
            model.put("user", personalService.getUserLK(principal));
            model.put("managers", personalService.getManagersAndCount().stream().sorted(Comparator.comparing(ManagersListDTO:: getReview1Month).reversed()));
            model.put("marketologs", personalService.getMarketologsAndCount().stream().sorted(Comparator.comparing(MarketologsListDTO:: getOrder1Month).reversed()));
            model.put("workers", personalService.gerWorkersToAndCount().stream().sorted(Comparator.comparing(WorkersListDTO:: getReview1Month).reversed()));
            model.put("operators", personalService.gerOperatorsAndCount().stream().sorted(Comparator.comparing(OperatorsListDTO:: getOrder1Month).reversed()));
            checkTimeMethod("Время выполнения AdminController/admin/score для ВСЕХ: ",startTime);
            return new ModelAndView("admin/layouts/score", model);
        }
    }

    @PostMapping("/score")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView scorePost(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber, @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);
        if ("ROLE_ADMIN".equals(userRole)) {
            model.put("user", personalService.getUserLK(principal));
            model.put("route", "score");
            model.put("managers", personalService.getManagersAndCountToDate(date).stream().sorted(Comparator.comparing(ManagersListDTO:: getSum1Month).reversed()));
            model.put("marketologs", personalService.getMarketologsAndCountToDate(date).stream().sorted(Comparator.comparing(MarketologsListDTO:: getSum1Month).reversed()));
            model.put("workers", personalService.gerWorkersToAndCountToDate(date).stream().sorted(Comparator.comparing(WorkersListDTO:: getSum1Month).reversed()));
            model.put("operators", personalService.gerOperatorsAndCountToDate(date).stream().sorted(Comparator.comparing(OperatorsListDTO:: getSum1Month).reversed()));
            checkTimeMethod("Время выполнения AdminController/admin/score для ADMIN: ",startTime);
            return new ModelAndView("admin/layouts/score", model);
        }
        else {
            model.put("route", "score");
            model.put("user", personalService.getUserLK(principal));
            model.put("managers", personalService.getManagersAndCountToDate(date).stream().sorted(Comparator.comparing(ManagersListDTO:: getReview1Month).reversed()));
            model.put("marketologs", personalService.getMarketologsAndCountToDate(date).stream().sorted(Comparator.comparing(MarketologsListDTO:: getOrder1Month).reversed()));
            model.put("workers", personalService.gerWorkersToAndCountToDate(date).stream().sorted(Comparator.comparing(WorkersListDTO:: getReview1Month).reversed()));
            model.put("operators", personalService.gerOperatorsAndCountToDate(date).stream().sorted(Comparator.comparing(OperatorsListDTO:: getOrder1Month).reversed()));
            checkTimeMethod("Время выполнения AdminController/admin/score для ВСЕХ: ",startTime);
            return new ModelAndView("admin/layouts/score", model);
        }
    }

    @GetMapping("/user_info")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ModelAndView userInfo(final Map<String, Object> model, @RequestParam(defaultValue = "") String staticFor, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        System.out.println(staticFor);
        model.put("route", "user_info");
        model.put("user", personalService.getUserLK(principal));
        model.put("workerZp", personalService.getWorkerReviews(staticFor));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @PostMapping("/user_info")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ModelAndView userInfoPost(final Map<String, Object> model, @RequestParam(defaultValue = "") String staticFor, Principal principal, @RequestParam(defaultValue = "0") int pageNumber,  @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();
        System.out.println(staticFor);
        model.put("route", "user_info");
        model.put("user", personalService.getUserLK(principal));
        model.put("workerZp", personalService.getWorkerReviews2(staticFor, date));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @GetMapping("/analyse")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ModelAndView analyseToAdmin(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
//        System.out.println(date);
        System.out.println(LocalDate.now());
        long startTime = System.nanoTime();
        model.put("route", "analyse");
        model.put("user", personalService.getUserLK(principal));
        model.put("stats", personalService.getStats());
        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ",startTime);
        return new ModelAndView("admin/layouts/analyse", model);
    }
    @PostMapping("/analyse")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ModelAndView analyseToAdmin(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber, @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();
        model.put("route", "analyse");
        model.put("user", personalService.getUserLK(principal));
        model.put("stats", personalService.getStats2(date));
        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ",startTime);
        return new ModelAndView("admin/layouts/analyse", model);
    }


    @GetMapping("/report")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ModelAndView reportToAdmin(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        model.put("route", "analyse");
        model.put("user", personalService.getUserLK(principal));
        model.put("stats", personalService.getStats());
        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ",startTime);
        return new ModelAndView("admin/layouts/analyse", model);
    }



//    @GetMapping("/personal")
//    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_MANAGER')")
//    public ModelAndView personal(final Map<String, Object> model, @RequestParam(defaultValue = "") String keyword, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
//        long startTime = System.nanoTime();
//        int pageSize = 10; // желаемый размер страницы
//        model.put("route", "fast_analytics");
//        model.put("managers", personalService.getManagers());
//        model.put("marketologs", personalService.getMarketologs());
//        model.put("workers", personalService.gerWorkers());
//        model.put("operators", personalService.gerOperators());
//        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime);
//        return new ModelAndView("admin/layouts/fast_analytics", model);
//    }



    private String gerRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя

    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }
}
