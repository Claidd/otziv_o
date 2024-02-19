package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.l_lead.services.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.util.*;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/companies")
public class CompanyController {

    private final CompanyService companyService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final PromoTextService promoTextService;

    int pageSize = 10; // желаемый размер страницы

//    СДЕЛАТЬ СОРТИРОВКУ ПО ВРЕМЕНИ ИЗМЕНЕНИЙ А НЕ СОЗДАНИЯ

    @GetMapping("/new_company") // Все компании - Новая
    public String NewAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "Новая", pageNumber, pageSize));
//            System.out.println(companyService.getAllCompaniesDTOList(keyword).stream().filter(company -> "Новая".equals(company.getStatus().getTitle())).sorted(Comparator.comparing(CompanyDTO::getCreateDate).reversed()).toList());
            model.addAttribute("urlFirst", "/companies/new_company");
            checkTimeMethod("Время выполнения CompanyController/new_company для Админа: ", startTime);
            return "companies/company/new_company_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "Новая", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/new_company");
            checkTimeMethod("Время выполнения CompanyController/new_company для Менеджера: ", startTime);
            return "companies/company/new_company_list";
        }

        else return "companies/company/company_list";
    } // Все компании - Новая

    @GetMapping("/on_work") // Все компании - В работе
    public String OnWorkAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "В работе");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "В работе", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/on_work");
            checkTimeMethod("Время выполнения CompanyController/on_work для Админа: ", startTime);
            return "companies/company/on_work_company_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "В работе");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "В работе", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/on_work");
            checkTimeMethod("Время выполнения CompanyController/on_work для Менеджера: ", startTime);
            return "companies/company/on_work_company_list";
        }
        else return "companies/company/company_list";
    } // Все компании - В работе

    @GetMapping("/on_stop") // Все компании - На стопе
    public String OnStopAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "На стопе");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "На стопе", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/on_stop");
            checkTimeMethod("Время выполнения CompanyController/on_work для Админа: ", startTime);
            return "companies/company/on_stop_company_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "В работе");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "На стопе", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/on_stop");
            checkTimeMethod("Время выполнения CompanyController/on_work для Менеджера: ", startTime);
            return "companies/company/on_stop_company_list";
        }
        else return "companies/company/company_list";
    } // Все компании - На стопе

    @GetMapping("/new_order") // Все компании - Новый заказ
    public String NewOrderAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Предложение нового заказа");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "Новый заказ", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/new_order");
            checkTimeMethod("Время выполнения CompanyController/new_order для Админа: ", startTime);
            return "companies/company/new_order_company_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Предложение нового заказа");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "Новый заказ", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/new_order");
            checkTimeMethod("Время выполнения CompanyController/new_order для Менеджера: ", startTime);
            return "companies/company/new_order_company_list";
        }
        else return "companies/company/company_list";
    } // Все компании - Новый заказ

    @GetMapping("/to_send") // Все компании - К рассылке
    public String ToSendAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "К рассылке");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToListToSend(keyword, "К рассылке", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/to_send");
            checkTimeMethod("Время выполнения CompanyController/to_send для Админа: ", startTime);
            return "companies/company/to_send_company_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "К рассылке");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManagerToSend(principal, keyword, "К рассылке", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/to_send");
            checkTimeMethod("Время выполнения CompanyController/to_send для Менеджера: ", startTime);
            return "companies/company/to_send_company_list";
        }
        else return "companies/company/company_list";
    } // Все компании - К рассылке

    @GetMapping("/ban_company") // Все компании - Бан
    public String BanAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Бан");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "Бан", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/ban_company");
            checkTimeMethod("Время выполнения CompanyController/ban_company для Админа: ", startTime);
            return "companies/company/ban_company_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Бан");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "Бан", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/ban_company");
            checkTimeMethod("Время выполнения CompanyController/ban_company для Менеджера: ", startTime);
            return "companies/company/ban_company_list";
        }
        else return "companies/company/company_list";
    } // Все компании - Бан

    @GetMapping("/waiting") // Все компании - Ожидание
    public String WaitingAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Ожидание");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "Ожидание", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/waiting");
            checkTimeMethod("Время выполнения CompanyController/waiting для Админа: ", startTime);
            return "companies/company/waiting_company_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Ожидание");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "Ожидание", pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/waiting");
            checkTimeMethod("Время выполнения CompanyController/waiting для Менеджера: ", startTime);
            return "companies/company/waiting_company_list";
        }
        else return "companies/company/waiting_company_list";
    } // Все компании - Ожидание

    @GetMapping("/allCompany") // список всех компаний
    public String CompanyList(@RequestParam(defaultValue = "") String keyword, Principal principal, Model model, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Все компании");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllCompaniesDTOList(keyword, pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/allCompany");
            checkTimeMethod("Время выполнения CompanyController/allCompany для Админа: ", startTime);
            return "companies/company/company_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Все компании");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("allCompany", companyService.getAllOrderDTOAndKeywordByManager(principal, keyword, pageNumber, pageSize));
            model.addAttribute("urlFirst", "/companies/allCompany");
            checkTimeMethod("Время выполнения CompanyController/allCompany для Менеджера: ", startTime);
            return "companies/company/company_list";
        }
        else return "companies/company/company_list";
    } // список всех компаний

    private String gerRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя

//    =========================================== COMPANY STATUS =======================================================


//    =========================================== COMPANY STATUS =======================================================
    @GetMapping("/editCompany/{companyId}")
    String ordersDetailsToCompany(@PathVariable Long companyId,  Model model) { // обновление компании -
        long startTime = System.nanoTime();
        CompanyDTO companyDTO = companyService.getCompaniesDTOById(companyId);
        model.addAttribute("companyDTO", companyDTO);
        model.addAttribute("categories", categoryService.getAllCategories());
        model.addAttribute("subCategories", subCategoryService.getSubcategoriesByCategoryId(companyDTO.getCategoryCompany().getId()));
        model.addAttribute("managers", managerService.getAllManagers());
        model.addAttribute("newWorkerDTO", new WorkerDTO());
        model.addAttribute("allWorkers", workerService.getAllWorkersByManagerId(companyDTO.getManager().getUser().getWorkers()));
        checkTimeMethod("Время выполнения CompanyController/editCompany/{companyId} для Всех: ", startTime);
        return "companies/company_edit";
    } // обновление компании -

    @PostMapping("/editCompany/{companyId}") // обновление компании - пост
    public String editCompany(@ModelAttribute ("companyDTO") CompanyDTO companyDTO, @ModelAttribute("newWorkerDTO") WorkerDTO newWorkerDTO,
                        @PathVariable Long companyId, RedirectAttributes rm, Model model){
        log.info("1. Начинаем обновлять данные компании");
        companyService.updateCompany(companyDTO, newWorkerDTO, companyId);
        log.info("5. Обновление компании прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/companies/editCompany/{companyId}";
    } // обновление компании - пост

    @GetMapping("/editCompany/{companyId}/deleteWorker/{workerId}") // удалить работника в компании
    public String editCompanyDeleteWorker(@PathVariable Long companyId, @PathVariable Long workerId, Model model){
        log.info("1. Начинаем удалять работника из списка работников компании");
        if (companyService.deleteWorkers(companyId, workerId)){
            log.info("4. Удаление работника из компании прошло успешно");
            return "redirect:/companies/editCompany/{companyId}";
        }
        else {
            log.info("4. Удаление работника из компании НЕ прошло успешно");
            return "redirect:/companies/editCompany/{companyId}";
        }
    } // удалить работника в компании

    @GetMapping("/editCompany/{companyId}/deleteFilial/{filialId}") // удалить филиал в компании
    public String editCompanyDeleteFilial(@PathVariable Long companyId, @PathVariable Long filialId, Model model){
        log.info("1. Начинаем удалять филиал из списка филиалов компании");
        if (companyService.deleteFilial(companyId, filialId)){
            log.info("4. Удаление филиала из компании прошло успешно");
            return "redirect:/companies/editCompany/{companyId}";
        }
        else {
            log.info("4. Удаление филиала из компании НЕ прошло успешно");
            return "redirect:/companies/editCompany/{companyId}";
        }
    } // удалить филиал в компании

    //    ==========================================================================================================
    @PostMapping ("/status_for_checking/{companyId}") // смена статуса на "Новая"
    public String changeStatusForChecking(@PathVariable Long companyId, Model model){
        if(companyService.changeStatusForCompany(companyId, "Новая")) {
            log.info("статус заказа успешно изменен на Новая");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Новая");
            return "products/orders_list";
        }
    } // смена статуса на "Новая"

    @PostMapping ("/status_for_waiting/{companyId}") // смена статуса на "Ожидание"
    public String changeStatusForWaiting(@PathVariable Long companyId, Model model){
        if(companyService.changeStatusForCompany(companyId, "Ожидание")) {
            log.info("статус заказа успешно изменен на Ожидание");
            return "redirect:/companies/new_order";
        } else {
            log.info("ошибка при изменении статуса заказа на Ожидание");
            return "redirect:/companies/new_order";
        }
    } // смена статуса на "Ожидание"

    @PostMapping ("/status_for_waiting_send/{companyId}") // смена статуса на "Ожидание"
    public String changeStatusForWaitingSend(@PathVariable Long companyId, Model model){
        if(companyService.changeStatusForCompany(companyId, "Ожидание")) {
            log.info("статус заказа успешно изменен на Ожидание");
            return "redirect:/companies/to_send";
        } else {
            log.info("ошибка при изменении статуса заказа на Ожидание");
            return "redirect:/companies/to_send";
        }
    } // смена статуса на "Ожидание"

    @PostMapping ("/status_for_stop/{companyId}") // смена статуса на "На стопе"
    public String changeStatusForStop(@PathVariable Long companyId, Model model){
        if(companyService.changeStatusForCompany(companyId, "На стопе")) {
            companyService.changeDataTry(companyId);
            log.info("статус заказа успешно изменен на На стопе");
            return "redirect:/companies/to_send";
        } else {
            log.info("ошибка при изменении статуса заказа на На стопе");
            return "redirect:/companies/new_order";
        }
    } // смена статуса на "На стопе"

    @PostMapping ("/status_for_ban/{companyId}") // смена статуса на "Бан"
    public String changeStatusForBan(@PathVariable Long companyId, Model model){
        if(companyService.changeStatusForCompany(companyId, "Бан")) {
            log.info("статус заказа успешно изменен на Бан");
            return "redirect:/companies/to_send";
        } else {
            log.info("ошибка при изменении статуса заказа на Бан");
            return "redirect:/companies/new_order";
        }
    } // смена статуса на "Бан"

    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }
}


