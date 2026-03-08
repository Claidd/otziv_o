package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.dto.presonal.*;
import com.hunt.otziv.admin.services.PersonalService;
//import com.hunt.otziv.u_users.config.DockerService;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.services.service.*;
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
    private final MarketologService marketologService;
    private final OperatorService operatorService;
    private final WorkerService workerService;

    @GetMapping()
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView lK(final Map<String, Object> model,
                           Principal principal,
                           @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);
        LocalDate localDate = LocalDate.now();

        model.put("route", "user_info");
        model.put("workerZp", personalService.getWorkerReviews(requestUser.user(), localDate));
        model.put("user", personalService.getUserLK(requestUser.user()));

        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ", startTime, principal);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @PostMapping()
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView lKPost(final Map<String, Object> model,
                               Principal principal,
                               @RequestParam(defaultValue = "0") int pageNumber,
                               @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);

        model.put("route", "user_info");
        model.put("workerZp", personalService.getWorkerReviews(requestUser.user(), date));
        model.put("user", personalService.getUserLK(requestUser.user()));

        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ", startTime, principal);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @GetMapping("/personal")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER')")
    public ModelAndView personal(final Map<String, Object> model,
                                 @RequestParam(defaultValue = "") String keyword,
                                 Principal principal,
                                 @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);
        User currentUser = requestUser.user();
        String userRole = requestUser.role();

        if ("ROLE_ADMIN".equals(userRole)) {
            model.put("route", "personal");
            model.put("user", personalService.getUserLK(currentUser));
            model.put("managers", personalService.getManagers());
            model.put("marketologs", personalService.getMarketologs());
            model.put("workers", personalService.gerWorkers());
            model.put("operators", personalService.gerOperators());

            checkTimeMethod("Время выполнения AdminController/admin/personal для Админа: ", startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }

        if ("ROLE_MANAGER".equals(userRole)) {
            Manager manager = managerService.getManagerByUserId(currentUser.getId());

            model.put("route", "personal");
            model.put("user", personalService.getUserLK(currentUser));
//            model.put("managers", personalService.getManagersToManager(manager));
            model.put("marketologs", personalService.getMarketologsToManager(manager));
            model.put("workers", personalService.gerWorkersToManager(manager));
            model.put("operators", personalService.gerOperatorsToManager(manager));

            checkTimeMethod("Время выполнения AdminController/admin/personal для Менеджера: ", startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }

        if ("ROLE_OWNER".equals(userRole)) {
            List<Manager> managerList = currentUser.getManagers() == null
                    ? Collections.emptyList()
                    : new ArrayList<>(currentUser.getManagers());

            List<Manager> managerList2 = personalService.findAllManagersWorkers(managerList);

            List<Marketolog> allMarketologs = managerList2.stream()
                    .flatMap(manager -> manager.getUser().getMarketologs().stream())
                    .toList();

            List<Operator> allOperators = managerList2.stream()
                    .flatMap(manager -> manager.getUser().getOperators().stream())
                    .toList();

            List<Worker> allWorkers = managerList2.stream()
                    .flatMap(manager -> manager.getUser().getWorkers().stream())
                    .toList();

            model.put("route", "personal");
            model.put("user", personalService.getUserLK(currentUser));
            model.put("managers", personalService.getManagersAndCountToOwner(managerList));
            model.put("marketologs", personalService.getMarketologsAndCountToOwner(allMarketologs));
            model.put("workers", personalService.getWorkersToAndCountToOwner(allWorkers));
            model.put("operators", personalService.getOperatorsAndCountToOwner(allOperators));

            checkTimeMethod("Время выполнения AdminController/admin/personal для Владельца: ", startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }

        return new ModelAndView("admin/layouts/personal", model);
    }

    @PostMapping("/personal")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER')")
    public ModelAndView personalPost(final Map<String, Object> model,
                                     @RequestParam(defaultValue = "") String keyword,
                                     Principal principal,
                                     @RequestParam(defaultValue = "0") int pageNumber,
                                     @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);
        User currentUser = requestUser.user();
        String userRole = requestUser.role();

        if ("ROLE_ADMIN".equals(userRole)) {
            model.put("route", "personal");
            model.put("user", personalService.getUserLK(currentUser));
            model.put("managers", personalService.getManagers());
            model.put("marketologs", personalService.getMarketologs());
            model.put("workers", personalService.gerWorkers());
            model.put("operators", personalService.gerOperators());

            checkTimeMethod("Время выполнения AdminController/admin/personal для Админа: ", startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }

        if ("ROLE_MANAGER".equals(userRole)) {
            Manager manager = managerService.getManagerByUserId(currentUser.getId());

            model.put("route", "personal");
            model.put("user", personalService.getUserLK(currentUser));
//            model.put("managers", personalService.getManagersToManager(manager));
            model.put("marketologs", personalService.getMarketologsToManager(manager));
            model.put("workers", personalService.gerWorkersToManager(manager));
            model.put("operators", personalService.gerOperatorsToManager(manager));

            checkTimeMethod("Время выполнения AdminController/admin/personal для Менеджера: ", startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }

        if ("ROLE_OWNER".equals(userRole)) {
            List<Manager> managerList = currentUser.getManagers() == null
                    ? Collections.emptyList()
                    : new ArrayList<>(currentUser.getManagers());

            List<Marketolog> allMarketologs = marketologService.getAllMarketologsToOwner(managerList);
            Set<Worker> allWorkers = workerService.getAllWorkersToManagerList(managerList);
            Set<Operator> allOperators = operatorService.getAllOperatorsToManagerList(managerList);

            model.put("route", "personal");
            model.put("user", personalService.getUserLK(currentUser));
            model.put("managers", personalService.getManagersAndCountToDateToOwner(managerList, date));
            model.put("marketologs", personalService.getMarketologsAndCountToDateToOwner(allMarketologs, date));
            model.put("workers", personalService.gerWorkersToAndCountToDateToOwner(new ArrayList<>(allWorkers), date));
            model.put("operators", personalService.gerOperatorsAndCountToDateToOwner(new ArrayList<>(allOperators), date));

            checkTimeMethod("Время выполнения AdminController/admin/personal для Владельца: ", startTime, principal);
            return new ModelAndView("admin/layouts/personal", model);
        }

        return new ModelAndView("admin/layouts/personal", model);
    }

    @GetMapping("/score")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView score(final Map<String, Object> model,
                              Principal principal,
                              @RequestParam(defaultValue = "0") int pageNumber) {
        return processScoreRequest(model, principal, LocalDate.now());
    }

    @PostMapping("/score")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_OPERATOR', 'ROLE_MARKETOLOG')")
    public ModelAndView scorePost(final Map<String, Object> model,
                                  Principal principal,
                                  @RequestParam(defaultValue = "0") int pageNumber,
                                  @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return processScoreRequest(model, principal, date);
    }

    private ModelAndView processScoreRequest(Map<String, Object> model, Principal principal, LocalDate date) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);

        model.put("route", "score");
        model.put("user", personalService.getUserLK(requestUser.user()));

        List<UserData> listPersonal = personalService.getPersonalsAndCountToScore(date);

        Map<String, List<UserData>> groupedUsers = listPersonal.stream()
                .collect(Collectors.groupingBy(
                        UserData::getRole,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.stream()
                                        .sorted(getRoleComparator())
                                        .collect(Collectors.toList())
                        )
                ));

        model.put("managers", groupedUsers.getOrDefault("ROLE_MANAGER", Collections.emptyList()));
        model.put("marketologs", groupedUsers.getOrDefault("ROLE_MARKETOLOG", Collections.emptyList()));
        model.put("workers", groupedUsers.getOrDefault("ROLE_WORKER", Collections.emptyList()));
        model.put("operators", groupedUsers.getOrDefault("ROLE_OPERATOR", Collections.emptyList()));

        checkTimeMethod("Время выполнения AdminController/admin/score: ", startTime, principal);
        return new ModelAndView("admin/layouts/score", model);
    }

    private Comparator<UserData> getRoleComparator() {
        return Comparator.comparing((UserData user) -> {
                    switch (user.getRole()) {
                        case "ROLE_MANAGER":
                            return 1;
                        case "ROLE_WORKER":
                            return 2;
                        case "ROLE_OPERATOR":
                            return 3;
                        case "ROLE_MARKETOLOG":
                            return 4;
                        default:
                            return 5;
                    }
                })
                .thenComparing(UserData::getTotalSum, Comparator.reverseOrder())
                .thenComparing(UserData::getSalary, Comparator.reverseOrder());
    }

    @GetMapping("/user_info")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER')")
    public ModelAndView userInfo(final Map<String, Object> model,
                                 @RequestParam(defaultValue = "") Long staticFor,
                                 Principal principal,
                                 @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);
        User userForInfo = userService.findByIdToUserInfo(staticFor);
        LocalDate localDate = LocalDate.now();

        model.put("route", "user_info");
        model.put("user", personalService.getUserLK(requestUser.user()));
        model.put("workerZp", personalService.getWorkerReviews(userForInfo, localDate));

        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ", startTime, principal);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @PostMapping("/user_info")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER')")
    public ModelAndView userInfoPost(final Map<String, Object> model,
                                     @RequestParam(defaultValue = "") Long staticFor,
                                     Principal principal,
                                     @RequestParam(defaultValue = "0") int pageNumber,
                                     @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Optional<LocalDate> date) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);
        User userForInfo = userService.findByIdToUserInfo(staticFor);
        LocalDate localDate = date.orElse(LocalDate.now());

        model.put("route", "user_info");
        model.put("user", personalService.getUserLK(requestUser.user()));
        model.put("workerZp", personalService.getWorkerReviews(userForInfo, localDate));

        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ", startTime, principal);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @GetMapping("/analyse")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER')")
    public ModelAndView analyseToAdmin(final Map<String, Object> model,
                                       Principal principal,
                                       @RequestParam(value = "date", required = false)
                                       @DateTimeFormat(pattern = "yyyy-MM-dd") Optional<LocalDate> date) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);
        LocalDate localDate = date.orElse(LocalDate.now());

        model.put("route", "analyse");
        model.put("user", personalService.getUserLK(requestUser.user()));
        model.put("stats", personalService.getStats(localDate, requestUser.user(), requestUser.role()));

        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ", startTime, principal);
        return new ModelAndView("admin/layouts/analyse", model);
    }

    @PostMapping("/analyse")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER')")
    public ModelAndView analyseToAdmin(final Map<String, Object> model,
                                       Principal principal,
                                       @RequestParam(defaultValue = "0") int pageNumber,
                                       @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);

        model.put("route", "analyse");
        model.put("user", personalService.getUserLK(requestUser.user()));
        model.put("stats", personalService.getStats(date, requestUser.user(), requestUser.role()));

        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ", startTime, principal);
        return new ModelAndView("admin/layouts/analyse", model);
    }

    @GetMapping("/report")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ModelAndView reportToAdmin(final Map<String, Object> model,
                                      Principal principal,
                                      @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();

        RequestUserContext requestUser = loadRequestUser(principal);
        LocalDate localDate = LocalDate.now();

        model.put("route", "analyse");
        model.put("user", personalService.getUserLK(requestUser.user()));
        model.put("stats", personalService.getStats(localDate, requestUser.user(), requestUser.role()));

        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ", startTime, principal);
        return new ModelAndView("admin/layouts/analyse", model);
    }

    private RequestUserContext loadRequestUser(Principal principal) {
        User currentUser = userService.findByUserName(principal.getName()).orElseThrow();
        String role = extractRole(currentUser);
        return new RequestUserContext(currentUser, role);
    }

    private String extractRole(User user) {
        if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
            return "";
        }

        return user.getRoles().stream()
                .findFirst()
                .map(Role::getAuthority)
                .orElse("");
    }

    private void checkTimeMethod(String text, long startTime, Principal principal) {
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        log.info("{}: {} сек. - {}", text, String.format("%.4f", timeElapsed), principal.getName());
    }

    private record RequestUserContext(User user, String role) {
    }
}