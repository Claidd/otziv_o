package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.sevices.CityService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.l_lead.services.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
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
    private final OrderService orderService;
    private final UserService userService;
    private final CityService cityService;

    int pageSize = 10; // желаемый размер страницы

//    СДЕЛАТЬ СОРТИРОВКУ ПО ВРЕМЕНИ ИЗМЕНЕНИЙ А НЕ СОЗДАНИЯ

    @GetMapping("/company") // Все компании - Новая
    public String AllCompanyList(@RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "Новая") String status, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
//        System.out.println(userRole);
        if (userRole.equals("ROLE_ADMIN") || userRole.equals("ROLE_MANAGER") || userRole.equals("ROLE_OWNER")) {
            getCompanyInfo(principal, model, userRole, keyword, status, pageNumber);
            checkTimeMethod("Время выполнения CompanyController/company (страница: companies/company/company_page) для " + userRole + ": ", startTime);
        }
        return "companies/company/company_page";
    } // Все компании - Новая

    private void getCompanyInfo(Principal principal, Model model, String userRole, String keyword, String status, int pageNumber) {
        model.addAttribute("TitleName", status);
        model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
        model.addAttribute("status", status);
        model.addAttribute("pageNumber", pageNumber);
        model.addAttribute("urlFirst", "/companies/company");
        if ("ROLE_ADMIN".equals(userRole)){
            checkAmountStatusCompany(principal, model, userRole);
            if (!"Все".equals(status)){
                model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, status, pageNumber, pageSize));
            } else {
                model.addAttribute("allCompany", companyService.getAllCompaniesDTOList(keyword, pageNumber, pageSize));
            }
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех компаний со статусом {} для Менеджера", status);
            checkAmountStatusCompany(principal, model, userRole);
            if (!"Все".equals(status)){
                model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize));
            } else {
                model.addAttribute("allCompany", companyService.getAllOrderDTOAndKeywordByManager(principal, keyword, pageNumber, pageSize));
            }
        }
        if ("ROLE_OWNER".equals(userRole)){
            log.info("Зашли в список всех компаний со статусом {} для Владельца", status);
            checkAmountStatusCompany(principal, model, userRole);
            if (!"Все".equals(status)){
                model.addAttribute("allCompany", companyService.getAllCompaniesDtoToOwner(principal, keyword, status, pageNumber, pageSize));
            } else {
                model.addAttribute("allCompany", companyService.getAllCompaniesDTOListOwner(principal, keyword, pageNumber, pageSize));
            }
        }
    }

    private void checkAmountStatusCompany(Principal principal, Model model, String userRole){
        if ("ROLE_ADMIN".equals(userRole)){
            model.addAttribute("to_check", createCheckNotification("В проверку"));
            model.addAttribute("on_check", createCheckNotification("На проверке"));
            model.addAttribute("correct", createCheckNotification("Коррекция"));
            model.addAttribute("published", createCheckNotification("Опубликовано"));
            model.addAttribute("archive", createCheckNotification("Архив"));
            model.addAttribute("new_order", createCheckNotificationCompany("Новый заказ"));
            model.addAttribute("on_work", createCheckNotificationCompany("В работе"));
        }
        if ("ROLE_MANAGER".equals(userRole)){
            Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
            model.addAttribute("to_check", createCheckNotificationToManager(manager,"В проверку"));
            model.addAttribute("on_check", createCheckNotificationToManager(manager,"На проверке"));
            model.addAttribute("correct", createCheckNotificationToManager(manager,"Коррекция"));
            model.addAttribute("published", createCheckNotificationToManager(manager,"Опубликовано"));
            model.addAttribute("archive", createCheckNotificationToManager(manager,"Архив"));
            model.addAttribute("new_order", createCheckNotificationToManagerCompany(manager,"Новый заказ"));
            model.addAttribute("on_work", createCheckNotificationToManagerCompany(manager,"В работе"));
        }
        if ("ROLE_OWNER".equals(userRole)){
            Set<Manager> managerList = userService.findByUserName(principal.getName()).orElseThrow().getManagers();
            model.addAttribute("to_check", createCheckNotificationToOwner(managerList,"В проверку"));
            model.addAttribute("on_check", createCheckNotificationToOwner(managerList,"На проверке"));
            model.addAttribute("correct", createCheckNotificationToOwner(managerList,"Коррекция"));
            model.addAttribute("published", createCheckNotificationToOwner(managerList,"Опубликовано"));
            model.addAttribute("archive", createCheckNotificationToOwner(managerList,"Архив"));
            model.addAttribute("new_order", createCheckNotificationToOwnerCompany(managerList,"Новый заказ"));
            model.addAttribute("on_work", createCheckNotificationToOwnerCompany(managerList,"В работе"));
        }
    }





//
//
//    @GetMapping("/new_company") // Все компании - Новая
//    public String NewAllCompanyList(@RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "Новая") String status, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
//        long startTime = System.nanoTime();
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//        if ("ROLE_ADMIN".equals(userRole)){
//            getCompanyInfo(principal, model, userRole, keyword, status, pageNumber);
//            checkTimeMethod("Время выполнения CompanyController/new_company для Админа: ", startTime);
//            return "companies/company/company_page";
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            getCompanyInfoToManager(principal, model, userRole, keyword, status, pageNumber);
//            checkTimeMethod("Время выполнения CompanyController/new_company для Менеджера: ", startTime);
//            return "companies/company/company_page";
//        }
//
//        if ("ROLE_OWNER".equals(userRole)){
//            getCompanyInfo(principal, model, userRole, keyword, status, pageNumber);
//            checkTimeMethod("Время выполнения CompanyController/new_company для Админа: ", startTime);
//            return "companies/company/company_page";
////            return "companies/company/new_company_list";
//        }
//
//        else return "companies/company/company_page";
//    } // Все компании - Новая
//
//
//
//    //        model.addAttribute("urlFirst", "/companies/on_work");
////            System.out.println(companyService.getAllCompaniesDTOList(keyword).stream().filter(company -> "Новая".equals(company.getStatus().getTitle())).sorted(Comparator.comparing(CompanyDTO::getCreateDate).reversed()).toList());
//
//
//
//    private void getCompanyInfoToManager(Principal principal, Model model, String userRole, String keyword, String status, int pageNumber) {
//        System.out.println(status);
//        log.info("Зашли в список всех компаний со статусом {} для Менеджера", status);
//        model.addAttribute("TitleName", status);
//        model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//        model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize));
//    }
//
//    private void getCompanyInfoToOwner(Principal principal, Model model, String userRole, String keyword, String status, int pageNumber) {
//        System.out.println(status);
//        log.info("Зашли в список всех компаний со статусом {} для Владельца", status);
//        model.addAttribute("to_check", createCheckNotificationToOwner(principal,"В проверку"));
//        model.addAttribute("published", createCheckNotificationToOwner(principal,"Опубликовано"));
//        model.addAttribute("new_order", createCheckNotificationToOwnerCompany(principal,"Новый заказ"));
//        model.addAttribute("on_work", createCheckNotificationToOwnerCompany(principal,"В работе"));
//        model.addAttribute("TitleName", status);
//        model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//        model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize));
//    }
//
//    @GetMapping("/on_work") // Все компании - В работе
//    public String OnWorkAllCompanyList(@RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "Новая") String status, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
//        long startTime = System.nanoTime();
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//        System.out.println(status);
//        if ("ROLE_ADMIN".equals(userRole)){
////            log.info("Зашли список всех компаний в работе для админа");
////            model.addAttribute("TitleName", "Компании в работе");
////            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
////            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "В работе", pageNumber, pageSize));
////            model.addAttribute("urlFirst", "/companies/on_work");
//            getCompanyInfo(principal, model, userRole, keyword, status, pageNumber);
//            checkTimeMethod("Время выполнения CompanyController/on_work для Админа: ", startTime);
//            return "companies/company/company_page";
////            return "companies/company/on_work_company_list";
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            log.info("Зашли список всех компаний в работе для Менеджера");
//            model.addAttribute("TitleName", "Компании в работе");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "В работе", pageNumber, pageSize));
//
//            checkTimeMethod("Время выполнения CompanyController/on_work для Менеджера: ", startTime);
//            return "companies/company/company_page";
//        }
//
//        if ("ROLE_OWNER".equals(userRole)){
////            log.info("Зашли список всех компаний в работе для админа");
////            model.addAttribute("TitleName", "Компании в работе");
////            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
////            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "В работе", pageNumber, pageSize));
////            model.addAttribute("urlFirst", "/companies/on_work");
////            checkTimeMethod("Время выполнения CompanyController/on_work для Админа: ", startTime);
//            getCompanyInfo(principal, model, userRole, keyword, status, pageNumber);
//            return "companies/company/company_page";
////            return "companies/company/on_work_company_list";
//        }
//        else return "companies/company/company_page";
//    } // Все компании - В работе
//
//    @GetMapping("/on_stop") // Все компании - На стопе
//    public String OnStopAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
//        long startTime = System.nanoTime();
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//
//        if ("ROLE_ADMIN".equals(userRole)){
//            log.info("Зашли список всех компаний на стопе для админа");
//            model.addAttribute("TitleName", "Компании на стопе");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "На стопе", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/on_stop");
//            checkTimeMethod("Время выполнения CompanyController/on_work для Админа: ", startTime);
//            return "companies/company/company_page";
////            return "companies/company/on_stop_company_list";
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            log.info("Зашли список всех компаний на стопе для Менеджера");
//            model.addAttribute("TitleName", "Компании на стопе");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "На стопе", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/on_stop");
//            checkTimeMethod("Время выполнения CompanyController/on_work для Менеджера: ", startTime);
//            return "companies/company/company_page";
//        }
//        else return "companies/company/company_page";
//    } // Все компании - На стопе
//
//    @GetMapping("/new_order") // Все компании - Новый заказ
//    public String NewOrderAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
//        long startTime = System.nanoTime();
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//
//        if ("ROLE_ADMIN".equals(userRole)){
//            log.info("Зашли список всех компаний для нового заказа для админа");
//            model.addAttribute("TitleName", "Предложение нового заказа комании");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "Новый заказ", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/new_order");
//            checkTimeMethod("Время выполнения CompanyController/new_order для Админа: ", startTime);
//            return "companies/company/company_page";
////            return "companies/company/new_order_company_list";
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            log.info("Зашли список всех компаний для нового заказа для Менеджера");
//            model.addAttribute("TitleName", "Предложение нового заказа комании");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "Новый заказ", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/new_order");
//            checkTimeMethod("Время выполнения CompanyController/new_order для Менеджера: ", startTime);
//            return "companies/company/company_page";
//        }
//        else return "companies/company/company_page";
//    } // Все компании - Новый заказ
//
//    @GetMapping("/to_send") // Все компании - К рассылке
//    public String ToSendAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
//        long startTime = System.nanoTime();
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//
//        if ("ROLE_ADMIN".equals(userRole)){
//            log.info("Зашли список всех компаний к рассылке для админа");
//            model.addAttribute("TitleName", "Компании к рассылке");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToListToSend(keyword, "К рассылке", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/to_send");
//            checkTimeMethod("Время выполнения CompanyController/to_send для Админа: ", startTime);
//            return "companies/company/company_page";
////            return "companies/company/to_send_company_list";
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            log.info("Зашли список всех компаний к рассылке для Менеджера");
//            model.addAttribute("TitleName", "Компании к рассылке");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManagerToSend(principal, keyword, "К рассылке", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/to_send");
//            checkTimeMethod("Время выполнения CompanyController/to_send для Менеджера: ", startTime);
//            return "companies/company/company_page";
//        }
//        else return "companies/company/company_list";
//    } // Все компании - К рассылке
//
//    @GetMapping("/ban_company") // Все компании - Бан
//    public String BanAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
//        long startTime = System.nanoTime();
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//
//        if ("ROLE_ADMIN".equals(userRole)){
//            log.info("Зашли список всех компаний в бане для админа");
//            model.addAttribute("TitleName", "Компании в бане");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "Бан", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/ban_company");
//            checkTimeMethod("Время выполнения CompanyController/ban_company для Админа: ", startTime);
//            return "companies/company/company_page";
////            return "companies/company/ban_company_list";
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            log.info("Зашли список всех компаний в бане для Менеджера");
//            model.addAttribute("TitleName", "Компании в бане");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "Бан", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/ban_company");
//            checkTimeMethod("Время выполнения CompanyController/ban_company для Менеджера: ", startTime);
//            return "companies/company/company_page";
//        }
//        else return "companies/company/company_page";
//    } // Все компании - Бан
//
//    @GetMapping("/waiting") // Все компании - Ожидание
//    public String WaitingAllCompanyList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
//        long startTime = System.nanoTime();
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//
//        if ("ROLE_ADMIN".equals(userRole)){
//            log.info("Зашли список всех компаний в ожидании для админа");
//            model.addAttribute("TitleName", "Компании в ожидании");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompaniesDTOListToList(keyword, "Ожидание", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/waiting");
//            checkTimeMethod("Время выполнения CompanyController/waiting для Админа: ", startTime);
//            return "companies/company/company_page";
////            return "companies/company/waiting_company_list";
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            log.info("Зашли список всех компаний в ожидании для Менеджера");
//            model.addAttribute("TitleName", "Компании в ожидании");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, "Ожидание", pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/waiting");
//            checkTimeMethod("Время выполнения CompanyController/waiting для Менеджера: ", startTime);
//            return "companies/company/company_page";
//        }
//        else return "companies/company/company_page";
//    } // Все компании - Ожидание
//
//    @GetMapping("/allCompany") // список всех компаний
//    public String CompanyList(@RequestParam(defaultValue = "") String keyword, Principal principal, Model model, @RequestParam(defaultValue = "0") int pageNumber){
//        long startTime = System.nanoTime();
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//        if ("ROLE_ADMIN".equals(userRole)){
//            log.info("Зашли список всех компаний для админа");
//            model.addAttribute("to_check", createCheckNotification("В проверку"));
//            model.addAttribute("published", createCheckNotification("Опубликовано"));
//            model.addAttribute("new_order", createCheckNotificationCompany("Новый заказ"));
//            model.addAttribute("on_work", createCheckNotificationCompany("В работе"));
//            model.addAttribute("TitleName", "Все компании");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllCompaniesDTOList(keyword, pageNumber, pageSize));
//            System.out.println(companyService.getAllCompaniesDTOList(keyword, pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/allCompany");
//            checkTimeMethod("Время выполнения CompanyController/allCompany для Админа: ", startTime);
//            return "companies/company/company_page";
////            return "companies/company/company_list";
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            log.info("Зашли список всех компаний для Менеджера");
//            model.addAttribute("to_check", createCheckNotificationToManager(principal,"В проверку"));
//            model.addAttribute("published", createCheckNotificationToManager(principal,"Опубликовано"));
//            model.addAttribute("new_order", createCheckNotificationToManagerCompany(principal,"Новый заказ"));
//            model.addAttribute("on_work", createCheckNotificationToManagerCompany(principal,"В работе"));
//            model.addAttribute("TitleName", "Все компании");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("allCompany", companyService.getAllOrderDTOAndKeywordByManager(principal, keyword, pageNumber, pageSize));
//            model.addAttribute("urlFirst", "/companies/allCompany");
//            checkTimeMethod("Время выполнения CompanyController/allCompany для Менеджера: ", startTime);
//            return "companies/company/company_page";
//        }
//        else return "companies/company/company_page";
//    } // список всех компаний

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
        model.addAttribute("subCategories", subCategoryService.getSubcategoriesByCategoryId(companyDTO.getCategoryCompany().getId()) != null ? subCategoryService.getSubcategoriesByCategoryId(companyDTO.getCategoryCompany().getId()) : 0L);
        model.addAttribute("managers", managerService.getAllManagers());
        model.addAttribute("newWorkerDTO", new WorkerDTO());
        model.addAttribute("allWorkers", workerService.getAllWorkersByManagerId(companyDTO.getManager().getUser().getWorkers()));
        List<CityDTO> citiesList = cityService.getAllCities().stream().sorted(Comparator.comparing(CityDTO::getCityTitle)).toList();
        model.addAttribute("cities", citiesList);
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
            log.info("статус Компании успешно изменен на Новая");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса Компании на Новая");
            return "products/orders_list";
        }
    } // смена статуса на "Новая"

    @PostMapping ("/status_for_waiting/{companyId}") // смена статуса на "Ожидание"
    public String changeStatusForWaiting(@PathVariable Long companyId, Model model, @RequestParam(defaultValue = "Все") String status, @RequestParam(defaultValue = "0") int pageNumber){
        if(companyService.changeStatusForCompany(companyId, "Ожидание")) {
            log.info("статус Компании успешно изменен на Ожидание");
            String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
            return "redirect:/companies/company?pageNumber=" + pageNumber + "&status=" + encodedStatus;
        } else {
            log.info("ошибка при изменении статуса Компании на Ожидание");
            return "redirect:/companies/company";
        }
    } // смена статуса на "Ожидание"

    @PostMapping ("/status_for_waiting_send/{companyId}") // смена статуса на "К рассылке"
    public String changeStatusForWaitingSend(@PathVariable Long companyId, Model model, @RequestParam(defaultValue = "Все") String status, @RequestParam(defaultValue = "0") int pageNumber){
        if(companyService.changeStatusForCompany(companyId, "Ожидание")) {
            log.info("статус Компании успешно изменен на Ожидание");
            String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
            return "redirect:/companies/company?pageNumber=" + pageNumber + "&status=" + encodedStatus;
        } else {
            log.info("ошибка при изменении статуса Компании на Ожидание");
            return "redirect:/companies/company";
        }
    } // смена статуса на "Ожидание"

    @PostMapping ("/status_for_stop/{companyId}") // смена статуса на "На стопе"
    public String changeStatusForStop(@PathVariable Long companyId, Model model, @RequestParam(defaultValue = "Все") String status, @RequestParam(defaultValue = "0") int pageNumber){
        if(companyService.changeStatusForCompany(companyId, "На стопе")) {
            companyService.changeDataTry(companyId);
            log.info("статус Компании успешно изменен на На стопе");
            String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
            return "redirect:/companies/company?pageNumber=" + pageNumber + "&status=" + encodedStatus;
        } else {
            log.info("ошибка при изменении статуса Компании на На стопе");
            return "redirect:/companies/company";
        }
    } // смена статуса на "На стопе"

    @PostMapping ("/status_for_ban/{companyId}") // смена статуса на "Бан"
    public String changeStatusForBan(@PathVariable Long companyId, Model model, @RequestParam(defaultValue = "Все") String status, @RequestParam(defaultValue = "0") int pageNumber ){
        if(companyService.changeStatusForCompany(companyId, "Бан")) {
            log.info("статус Компании успешно изменен на Бан");
            String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
            return "redirect:/companies/company?pageNumber=" + pageNumber + "&status=" + encodedStatus;
        } else {
            log.info("ошибка при изменении статуса Компании на Бан");
            return "redirect:/companies/company";
        }
    } // смена статуса на "Бан"

    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }


    private int createCheckNotification(String status) {
        return orderService.getAllOrderDTOByStatus(status);
    }

    private int createCheckNotificationToManager(Manager manager, String status) {
        return orderService.getAllOrderDTOByStatusToManager(manager, status);
    }


    private int createCheckNotificationCompany(String status) {
        return companyService.getAllCompanyDTOByStatus(status);
    }

    private int createCheckNotificationToManagerCompany(Manager manager, String status) {
        return companyService.getAllCompanyDTOByStatusToManager(manager, status);
    }

    private int createCheckNotificationToOwner(Set<Manager> managerList, String status) {
        return orderService.getAllOrderDTOByStatusToOwner(managerList, status);
    }

    private int createCheckNotificationToOwnerCompany(Set<Manager> managerList, String status) {
        return companyService.getAllCompanyDTOByStatusToOwner(managerList, status);
    }

}


