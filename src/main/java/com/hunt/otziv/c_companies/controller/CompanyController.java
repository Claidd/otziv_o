package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @GetMapping("/allCompany")
    String CompanyList(Model model){
        model.addAttribute("allCompanyNew", companyService.getAllCompaniesDTO());
        return "companies/company_list";
    }

    @GetMapping("/editCompany/{companyId}")
    String ordersDetailsToCompany(@PathVariable Long companyId, Model model){
        CompanyDTO companyDTO = companyService.getCompaniesDTOById(companyId);

        model.addAttribute("companyDTO", companyDTO);
        model.addAttribute("categories", categoryService.getAllCategories());
        model.addAttribute("subCategories", subCategoryService.getSubcategoriesByCategoryId(companyDTO.getCategoryCompany().getId()));
        model.addAttribute("managers", managerService.getAllManagers());
        model.addAttribute("newWorkerDTO", new WorkerDTO());
        List<String> list = new ArrayList<>();
        model.addAttribute("ListWorker", list);
//        model.addAttribute("newFilialDTO" , new FilialDTO());
        model.addAttribute("allWorkers", workerService.getAllWorkersByManagerId(companyDTO.getManager().getUser().getWorkers()));
        return "companies/company_edit";
    }

    @PostMapping("/editCompany/{companyId}")
    String editCompany(@ModelAttribute ("companyDTO") CompanyDTO companyDTO, @ModelAttribute("newWorkerDTO") WorkerDTO newWorkerDTO,
                        @PathVariable Long companyId, Model model){
        log.info("1. Начинаем обновлять данные компании");
        companyService.updateCompany(companyDTO, newWorkerDTO, companyId);
        log.info("5. Обновление компании прошло успешно");
        return "redirect:/companies/editCompany/{companyId}";
    }

    @GetMapping("/editCompany/{companyId}/deleteWorker/{workerId}")
    String editCompanyDeleteWorker(@PathVariable Long companyId, @PathVariable Long workerId, Model model){
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

    @GetMapping("/editCompany/{companyId}/deleteFilial/{filialId}")
    String editCompanyDeleteFilial(@PathVariable Long companyId, @PathVariable Long filialId, Model model){
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

}
