package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.dto.presonal.*;
import com.hunt.otziv.admin.services.PersonalService;
//import com.hunt.otziv.u_users.config.DockerService;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j;
import lombok.extern.slf4j.Slf4j;
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

import java.io.IOException;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final PersonalService personalService;
    private final UserService userService;
    private final ManagerService managerService;



    @GetMapping()
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView lK(final Map<String, Object> model,  Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        User user = userService.findByUserName(principal.getName()).orElseThrow();
        LocalDate localDate = LocalDate.now();
        model.put("route", "user_info");
        model.put("workerZp", personalService.getWorkerReviews(user, localDate));
        model.put("user", personalService.getUserLK(principal));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime, principal);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @PostMapping()
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView lKPost(final Map<String, Object> model,  Principal principal, @RequestParam(defaultValue = "0") int pageNumber, @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();
        User user = userService.findByUserName(principal.getName()).orElseThrow();
        model.put("route", "user_info");
        model.put("workerZp", personalService.getWorkerReviews(user, date));
        model.put("user", personalService.getUserLK(principal));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime, principal);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @GetMapping("/personal")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER')")
    public ModelAndView personal(final Map<String, Object> model, @RequestParam(defaultValue = "") String keyword, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        String userRole = getRole(principal);
        if ("ROLE_ADMIN".equals(userRole)) {
            model.put("route", "personal");
            model.put("user", personalService.getUserLK(principal));
            model.put("managers", personalService.getManagers());
            model.put("marketologs", personalService.getMarketologs());
            model.put("workers", personalService.gerWorkers());
            model.put("operators", personalService.gerOperators());
            checkTimeMethod("Время выполнения AdminController/admin/personal для Админа: ",startTime, principal);
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
            checkTimeMethod("Время выполнения AdminController/admin/personal для Менеджера: ",startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }

        if ("ROLE_OWNER".equals(userRole)) {
            List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
            List<Manager> managerList2 = personalService.findAllManagersWorkers(managerList);
            // Получение списка всех маркетологов
            List<Marketolog> allMarketologs = managerList2.stream().flatMap(manager -> manager.getUser().getMarketologs().stream()).toList();
            // Получение списка всех операторов
            List<Operator> allOperators = managerList2.stream().flatMap(manager -> manager.getUser().getOperators().stream()).toList();
            // Получение списка всех работников
            List<Worker> allWorkers = managerList2.stream().flatMap(manager -> manager.getUser().getWorkers().stream()).toList();// Вывод списков

            model.put("route", "personal");
            model.put("user", personalService.getUserLK(principal));
            model.put("managers", personalService.getManagersAndCountToOwner(managerList));
            model.put("marketologs", personalService.getMarketologsAndCountToOwner(allMarketologs));
            model.put("workers", personalService.getWorkersToAndCountToOwner(allWorkers));
            model.put("operators", personalService.getOperatorsAndCountToOwner(allOperators));
            checkTimeMethod("Время выполнения AdminController/admin/personal для Владельца: ",startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }
        else return new ModelAndView("admin/layouts/personal", model);
    }

    @PostMapping("/personal")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER')")
    public ModelAndView personalPost(final Map<String, Object> model, @RequestParam(defaultValue = "") String keyword, Principal principal, @RequestParam(defaultValue = "0") int pageNumber, @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();
        String userRole = getRole(principal);
        if ("ROLE_ADMIN".equals(userRole)) {
            model.put("route", "personal");
            model.put("user", personalService.getUserLK(principal));
            model.put("managers", personalService.getManagers());
            model.put("marketologs", personalService.getMarketologs());
            model.put("workers", personalService.gerWorkers());
            model.put("operators", personalService.gerOperators());
            checkTimeMethod("Время выполнения AdminController/admin/personal для Админа: ",startTime, principal);
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
            checkTimeMethod("Время выполнения AdminController/admin/personal для Менеджера: ",startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }

        if ("ROLE_OWNER".equals(userRole)) {
            List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
            List<Manager> managerList2 = personalService.findAllManagersWorkers(managerList);
            // Получение списка всех маркетологов
            List<Marketolog> allMarketologs = managerList2.stream().flatMap(manager -> manager.getUser().getMarketologs().stream()).toList();
            // Получение списка всех операторов
            List<Operator> allOperators = managerList2.stream().flatMap(manager -> manager.getUser().getOperators().stream()).toList();
            // Получение списка всех работников
            List<Worker> allWorkers = managerList2.stream().flatMap(manager -> manager.getUser().getWorkers().stream()).toList();// Вывод списков

            model.put("route", "personal");
            model.put("user", personalService.getUserLK(principal));
            model.put("managers", personalService.getManagersAndCountToDateToOwner(managerList, date));
            model.put("marketologs", personalService.getMarketologsAndCountToDateToOwner(allMarketologs, date));
            model.put("workers", personalService.gerWorkersToAndCountToDateToOwner(allWorkers, date));
            model.put("operators", personalService.gerOperatorsAndCountToDateToOwner(allOperators, date));
            checkTimeMethod("Время выполнения AdminController/admin/personal для Владельца: ",startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }
        else return new ModelAndView("admin/layouts/personal", model);
    }

    @GetMapping("/score")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView score(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        return processScoreRequest(model, principal, LocalDate.now());
    }

    @PostMapping("/score")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView scorePost(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber,
                                  @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return processScoreRequest(model, principal, date);
    }

    private ModelAndView processScoreRequest(Map<String, Object> model, Principal principal, LocalDate date) {
        long startTime = System.nanoTime();
        String userRole = getRole(principal);

        model.put("route", "score");
        model.put("user", personalService.getUserLK(principal));

        // Получаем всех пользователей
        List<UserData> listPersonal = personalService.getPersonalsAndCountToScore(date);

        // Группировка пользователей по ролям
        Map<String, List<UserData>> groupedUsers = listPersonal.stream()
                .collect(Collectors.groupingBy(
                        UserData::getRole,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.stream()
                                        .sorted(getRoleComparator()) // Универсальная сортировка
                                        .collect(Collectors.toList())
                        )
                ));

        // Добавляем группы пользователей в модель
        model.put("managers", groupedUsers.getOrDefault("ROLE_MANAGER", Collections.emptyList()));
        model.put("marketologs", groupedUsers.getOrDefault("ROLE_MARKETOLOG", Collections.emptyList()));
        model.put("workers", groupedUsers.getOrDefault("ROLE_WORKER", Collections.emptyList()));
        model.put("operators", groupedUsers.getOrDefault("ROLE_OPERATOR", Collections.emptyList()));

        checkTimeMethod("Время выполнения AdminController/admin/score: ", startTime, principal);
        return new ModelAndView("admin/layouts/score", model);
    }

    // Универсальный компаратор для сортировки
    private Comparator<UserData> getRoleComparator() {
        return Comparator.comparing((UserData user) -> {
                    switch (user.getRole()) {
                        case "ROLE_MANAGER": return 1;
                        case "ROLE_WORKER": return 2;
                        case "ROLE_OPERATOR": return 3;
                        case "ROLE_MARKETOLOG": return 4;
                        default: return 5;
                    }
                }).thenComparing(UserData::getTotalSum, Comparator.reverseOrder())
                .thenComparing(UserData::getSalary, Comparator.reverseOrder());
    }


//    @GetMapping("/score")
//    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
//    public ModelAndView score(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
//        long startTime = System.nanoTime();
//        String userRole = getRole(principal);
//        LocalDate localDate = LocalDate.now();
//        return getListPersonalToScore(model, principal, startTime, userRole, localDate);
//    }
//
//    @PostMapping("/score")
//    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
//    public ModelAndView scorePost(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber, @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
//        long startTime = System.nanoTime();
//        String userRole = getRole(principal);
//        return getListPersonalToScore(model, principal, startTime, userRole, date);
//    }
//
//    private ModelAndView getListPersonalToScore(Map<String, Object> model, Principal principal, long startTime, String userRole, LocalDate localDate) {
//        List<UserData> listPersonal = personalService.getPersonalsAndCountToScore(localDate);
//
//        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole)) {
//            model.put("user", personalService.getUserLK(principal));
//            model.put("route", "score");
//            // Получаем все персональные данные
//
//            // Фильтруем пользователей по ролям
//            List<UserData> managers = listPersonal.stream()
//                    .filter(user -> "ROLE_MANAGER".equals(user.getRole()))
//                    .sorted(Comparator.comparing(UserData:: getTotalSum).reversed())
//                    .collect(Collectors.toList());
//            List<UserData> workers = listPersonal.stream()
//                    .filter(user -> "ROLE_WORKER".equals(user.getRole()))
//                    .sorted(Comparator.comparing(UserData:: getSalary).reversed())
//                    .collect(Collectors.toList());
//            List<UserData> marketologs = listPersonal.stream()
//                    .filter(user -> "ROLE_MARKETOLOG".equals(user.getRole()))
//                    .collect(Collectors.toList());
//
//            List<UserData> operators = listPersonal.stream()
//                    .filter(user -> "ROLE_OPERATOR".equals(user.getRole()))
//                    .collect(Collectors.toList());
//
//            // Добавляем в модель
//            model.put("managers", managers);
//            model.put("marketologs", marketologs);
//            model.put("workers", workers);
//            model.put("operators", operators);
//            checkTimeMethod("Время выполнения AdminController/admin/score для ADMIN: ",startTime);
//            return new ModelAndView("admin/layouts/score", model);
//        }
//        else {
//            model.put("route", "score");
//            model.put("user", personalService.getUserLK(principal));
//            // Получаем все персональные данные
//
//
//            // Фильтруем пользователей по ролям
//            List<UserData> managers = listPersonal.stream()
//                    .filter(user -> "ROLE_MANAGER".equals(user.getRole()))
//                    .sorted(Comparator.comparing(UserData:: getTotalSum).reversed())
//                    .collect(Collectors.toList());
//            List<UserData> workers = listPersonal.stream()
//                    .filter(user -> "ROLE_WORKER".equals(user.getRole()))
//                    .sorted(Comparator.comparing(UserData:: getSalary).reversed())
//                    .collect(Collectors.toList());
//            List<UserData> marketologs = listPersonal.stream()
//                    .filter(user -> "ROLE_MARKETOLOG".equals(user.getRole()))
//                    .collect(Collectors.toList());
//
//            List<UserData> operators = listPersonal.stream()
//                    .filter(user -> "ROLE_OPERATOR".equals(user.getRole()))
//                    .collect(Collectors.toList());
//
//            // Добавляем в модель
//            model.put("managers", managers);
//            model.put("marketologs", marketologs);
//            model.put("workers", workers);
//            model.put("operators", operators);
////            model.put("managers", personalService.getManagersAndCount().stream().sorted(Comparator.comparing(ManagersListDTO:: getReview1Month).reversed()));
////            model.put("marketologs", personalService.getMarketologsAndCount().stream().sorted(Comparator.comparing(MarketologsListDTO:: getOrder1Month).reversed()));
////            model.put("workers", personalService.gerWorkersToAndCount().stream().sorted(Comparator.comparing(WorkersListDTO:: getReview1Month).reversed()));
////            model.put("operators", personalService.gerOperatorsAndCount().stream().sorted(Comparator.comparing(OperatorsListDTO:: getOrder1Month).reversed()));
//            checkTimeMethod("Время выполнения AdminController/admin/score для ВСЕХ: ",startTime);
//            return new ModelAndView("admin/layouts/score", model);
//        }
//    }



    @GetMapping("/user_info")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER')")
    public ModelAndView userInfo(final Map<String, Object> model, @RequestParam(defaultValue = "") Long staticFor, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        LocalDate localDate = LocalDate.now();
//        System.out.println(staticFor);
        User user = userService.findByIdToUserInfo(staticFor);
        model.put("route", "user_info");
        model.put("user", personalService.getUserLK(principal));
        model.put("workerZp", personalService.getWorkerReviews(user, localDate));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime, principal);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @PostMapping("/user_info")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER')")
    public ModelAndView userInfoPost(final Map<String, Object> model, @RequestParam(defaultValue = "") Long staticFor, Principal principal, @RequestParam(defaultValue = "0") int pageNumber,  @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Optional<LocalDate> date) {
        long startTime = System.nanoTime();
        LocalDate localDate = date.orElse(LocalDate.now());
//        System.out.println(staticFor);
        User user = userService.findByIdToUserInfo(staticFor);
        model.put("route", "user_info");
        model.put("user", personalService.getUserLK(principal));
        model.put("workerZp", personalService.getWorkerReviews(user, localDate));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime, principal);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @GetMapping("/analyse")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER')")
    public ModelAndView analyseToAdmin(final Map<String, Object> model, Principal principal, @RequestParam(value = "date", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") Optional<LocalDate> date) throws IOException {
        LocalDate localDate = date.orElse(LocalDate.now());
        String userRole = getRole(principal);
        User user = userService.findByUserName(principal.getName()).orElseThrow();
        long startTime = System.nanoTime();

        if ("ROLE_ADMIN".equals(userRole)) {
            model.put("route", "analyse");
            model.put("user", personalService.getUserLK(principal));
            model.put("stats", personalService.getStats(localDate, user, userRole));
//            dockerService.createMysqlBackup("mysql_container", "tmp/backup-otziv.sql");
            checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ",startTime, principal);
            return new ModelAndView("admin/layouts/analyse", model);
        }
        if ("ROLE_OWNER".equals(userRole)) {
            model.put("route", "analyse");
            model.put("user", personalService.getUserLK(principal));
            model.put("stats", personalService.getStats(localDate, user, userRole));
            checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ",startTime, principal);
            return new ModelAndView("admin/layouts/analyse", model);
        }
        return new ModelAndView("admin/layouts/analyse", model);
    }
    @PostMapping("/analyse")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER')")
    public ModelAndView analyseToAdmin(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber, @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();
        String userRole = getRole(principal);
        User user = userService.findByUserName(principal.getName()).orElseThrow();
        model.put("route", "analyse");
        model.put("user", personalService.getUserLK(principal));
        model.put("stats", personalService.getStats(date, user, userRole));
        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ",startTime, principal);
        return new ModelAndView("admin/layouts/analyse", model);
    }

    @GetMapping("/report")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ModelAndView reportToAdmin(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        LocalDate localDate = LocalDate.now();
        User user = userService.findByUserName(principal.getName()).orElseThrow();
        model.put("route", "analyse");
        model.put("user", personalService.getUserLK(principal));
        model.put("stats", personalService.getStats(localDate, user, "ROLE_ADMIN"));
        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ",startTime, principal);
        return new ModelAndView("admin/layouts/analyse", model);
    }



    private String getRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя

    private void checkTimeMethod(String text, long startTime , Principal principal){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        log.info("{}: {} сек. - {}", text, String.format("%.4f", timeElapsed), principal.getName());
    }
}
