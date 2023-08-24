package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
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
public class CompanyController {

    private final CompanyService companyService;
    private final LeadService leadService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final WorkerService workerService;


    @GetMapping("/new_company_to_manager/{leadId}")
    String newCompanyToManager(@PathVariable final Long leadId, Principal principal, Model model){
        model.addAttribute("newCompany", companyService.convertToDtoToManager(leadId, principal));
        List<CategoryDTO> categories = categoryService.getAllCategories();
        model.addAttribute("categories", categories);
        model.addAttribute("workers", workerService.getAllWorkers());
        return "companies/new_company_to_manager";
    }

    @GetMapping("/getSubcategories")
    @ResponseBody
    public List<SubCategoryDTO> getSubcategoriesByCategoryId(@RequestParam Long categoryId) {
        return subCategoryService.getSubcategoriesByCategoryId(categoryId);
    }
}
