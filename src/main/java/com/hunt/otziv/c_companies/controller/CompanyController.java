package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_companies.services.CompanyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.security.Principal;
import java.util.List;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/companies")
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping("/allCompany")
    String CompanyList(Model model){
        model.addAttribute("allCompanyNew", companyService.getAllCompaniesDTO());
        return "companies/company_list";
    }



    @GetMapping("/ordersDetails/{id}")
    String ordersDetailsToCompany(@PathVariable Long id, Model model){
        model.addAttribute("allCompanyNew", companyService.getAllCompaniesDTO());
        return "companies/company_list";
    }
}
