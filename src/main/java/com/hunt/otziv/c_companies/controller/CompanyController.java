package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
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
    private final CompanyStatusService companyStatusService;

    //    =========================================== COMPANY STATUS =======================================================
    @GetMapping("/allCompany") // список всех компаний c ПОИСКОМ
    public String CompanyList(@RequestParam(defaultValue = "") String keyword, Model model){
//        model.addAttribute("allCompanyNew", companyService.getAllCompaniesDTO());
        model.addAttribute("allCompanyNew", companyService.getAllCompaniesDTO(keyword));
        return "companies/company_list";
    }

//    =========================================== COMPANY STATUS =======================================================
    @GetMapping("/editCompany/{companyId}")
    String ordersDetailsToCompany(@PathVariable Long companyId,  Model model){
        CompanyDTO companyDTO = companyService.getCompaniesDTOById(companyId);
        model.addAttribute("companyDTO", companyDTO);
        model.addAttribute("categories", categoryService.getAllCategories());
        model.addAttribute("subCategories", subCategoryService.getSubcategoriesByCategoryId(companyDTO.getCategoryCompany().getId()));
        model.addAttribute("managers", managerService.getAllManagers());
        model.addAttribute("newWorkerDTO", new WorkerDTO());
        model.addAttribute("allWorkers", workerService.getAllWorkersByManagerId(companyDTO.getManager().getUser().getWorkers()));
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
    }

    @GetMapping("/editCompany/{companyId}/deleteWorker/{workerId}")// удалить работника в компании
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
    }

    @GetMapping("/editCompany/{companyId}/deleteFilial/{filialId}")// удалить филиал в компании
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
    }

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
    }
}


