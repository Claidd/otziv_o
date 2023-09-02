package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/companies")
public class CompanyEditorController {

    private final CompanyService companyService;
    private final LeadService leadService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final WorkerService workerService;
    private final FilialService filialService;
    private final CompanyRepository companyRepository;


    @GetMapping("/new_company_to_manager/{leadId}")
    String newCompanyToManager(@PathVariable final Long leadId, Principal principal, Model model){
        model.addAttribute("newCompany", companyService.convertToDtoToManager(leadId, principal));
        List<CategoryDTO> categories = categoryService.getAllCategories();
        model.addAttribute("categories", categories);
        model.addAttribute("workers", workerService.getAllWorkers());
        model.addAttribute("leadId", leadId);
        return "companies/new_company_to_manager";
    }

    @PostMapping("/new_company_to_manager")
    String saveNewCompanyToManager(Principal principal, @ModelAttribute("newCompany") CompanyDTO companyDTO, @ModelAttribute("leadId") Long leadId){
        log.info("1.Начинаем сохранение компании");
        if (companyService.save(companyDTO)){
            log.info("OK.Начинаем сохранение компании прошло успешно");
            for (Company company: companyService.getAllCompanies()) {
                log.info("вход в меняем статус с К рассылке на В работе");
                leadService.changeStatusLeadOnInWork(leadId);
                log.info("статус успешно сменен К рассылке на В работе" );
                System.out.println(company);
            }
            return "redirect:/lead";
        }
        else {
            log.info("ERROR.Начинаем сохранение компании прошло НЕ успешно");
            return "redirect:/lead";
        }
    }

    @GetMapping("/getSubcategories")
    @ResponseBody
    public List<SubCategoryDTO> getSubcategoriesByCategoryId(@RequestParam Long categoryId) {
        return subCategoryService.getSubcategoriesByCategoryId(categoryId);
    }
}
