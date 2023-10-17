package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Comparator;
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
    private final UserService userService;


    @GetMapping("/new_company_to_manager/{leadId}")
    String newCompanyToManager(@PathVariable final Long leadId, Principal principal, Model model) { // Добавление новой компании из лида Менеджера
        model.addAttribute("newCompany", companyService.convertToDtoToManager(leadId, principal));
        List<CategoryDTO> categories = categoryService.getAllCategories().stream().sorted(Comparator.comparing(CategoryDTO::getCategoryTitle)).toList();
        model.addAttribute("categories", categories);
        model.addAttribute("workers", userService.findByUserName(principal.getName()).orElseThrow().getWorkers().stream().toList());
        model.addAttribute("leadId", leadId);
        return "companies/new_company_to_manager";
    } // Добавление новой компании из лида Менеджера

    @PostMapping("/new_company_to_manager")
    String saveNewCompanyToManager(Principal principal, @ModelAttribute("newCompany") CompanyDTO companyDTO, @ModelAttribute("leadId") Long leadId) { // Добавление новой компании из лида Менеджера
        log.info("1.Начинаем сохранение компании");
        if (companyService.save(companyDTO)) {
            log.info("OK.Начинаем сохранение компании прошло успешно");
            for (Company company : companyService.getAllCompaniesList()) {
                log.info("вход в меняем статус с К рассылке на В работе");
                leadService.changeStatusLeadOnInWork(leadId);
                log.info("статус успешно сменен К рассылке на В работе");
                System.out.println(company);
            }
            return "redirect:/companies/new_company";
        } else {
            log.info("ERROR.Начинаем сохранение компании прошло НЕ успешно");
            return "redirect:/lead";
        }
    } // Добавление новой компании из лида Менеджера

    @GetMapping("/getSubcategories")
    @ResponseBody
    public List<SubCategoryDTO> getSubcategoriesByCategoryId(@RequestParam Long categoryId) { // Подгрузка подкатегорий
        return subCategoryService.getSubcategoriesByCategoryId(categoryId).stream().sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle)).toList();
    } // Подгрузка подкатегорий
}



