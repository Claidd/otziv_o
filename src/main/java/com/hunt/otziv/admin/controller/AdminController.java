package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.services.PersonalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.security.Principal;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final PersonalService personalService;


//    @GetMapping() //Открываем главную страницу
//    String adminPanel(Model model){
//        return "admin/admin_panel";
//    }

    @GetMapping()
    public ModelAndView personal(final Map<String, Object> model, @RequestParam(defaultValue = "") String keyword, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        int pageSize = 10; // желаемый размер страницы
        model.put("route", "fast_analytics");
        model.put("managers", personalService.getManagers());
        model.put("marketologs", personalService.getMarketologs());
        model.put("workers", personalService.gerWorkers());
        model.put("operators", personalService.gerOperators());

        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime);
        return new ModelAndView("admin/layouts/fast_analytics", model);
    }

    @GetMapping("/user_info")
    public ModelAndView userInfo(final Map<String, Object> model, @RequestParam(defaultValue = "") String staticFor, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        System.out.println(staticFor);
        model.put("route", "user_info");
        model.put("workerZp", personalService.getWorkerReviews(staticFor));
        checkTimeMethod("Время выполнения AdminController/admin/personal для всех: ",startTime);
        return new ModelAndView("admin/layouts/user_info", model);
    }

    @GetMapping("/analyse")
    public ModelAndView analyseToAdmin(final Map<String, Object> model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber) {
        long startTime = System.nanoTime();
        model.put("route", "analyse");
        model.put("stats", personalService.getStats());
        checkTimeMethod("Время выполнения AdminController/admin/analyse для всех: ",startTime);
        return new ModelAndView("admin/layouts/analyse", model);
    }






    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }
}
