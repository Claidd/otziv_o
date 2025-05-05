package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.sevices.CityService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.c_companies.util.CompanyValidation;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
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
    private final CityService cityService;
    private final CompanyValidation companyValidation;

    @GetMapping("/new_company_to_operator/{leadId}")
    String newCompanyToOperator(@PathVariable final Long leadId, Principal principal, Model model) { // Добавление новой компании из лида Менеджера
        model.addAttribute("newCompany", companyService.convertToDtoToOperator(leadId, principal));
        List<CategoryDTO> categories = categoryService.getAllCategories().stream().sorted(Comparator.comparing(CategoryDTO::getCategoryTitle)).toList();
        model.addAttribute("categories", categories);
        List<CityDTO> citiesList = cityService.getAllCities().stream().sorted(Comparator.comparing(CityDTO::getCityTitle)).toList();
        model.addAttribute("cities", citiesList);
        model.addAttribute("leadId", leadId);
        return "companies/new_company_to_operator";
    } // Добавление новой компании из лида Менеджера

    @PostMapping("/new_company_to_operator")
    String saveNewCompanyToOperator(Principal principal, @ModelAttribute("newCompany") @Valid CompanyDTO companyDTO, BindingResult bindingResult, @ModelAttribute("leadId") Long leadId, Model model) { // Добавление новой компании из лида Менеджера
        log.info("1.Начинаем сохранение компании");
        companyValidation.validate(companyDTO, bindingResult);
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            CompanyDTO companyDTO1 = companyService.convertToDtoToManager(leadId, principal);
            companyDTO.setUser(companyDTO1.getUser());
            model.addAttribute("newCompany", companyDTO);
            List<CategoryDTO> categories = categoryService.getAllCategories().stream().sorted(Comparator.comparing(CategoryDTO::getCategoryTitle)).toList();
            model.addAttribute("categories", categories);
            model.addAttribute("leadId", leadId);
            List<CityDTO> citiesList = cityService.getAllCities().stream().sorted(Comparator.comparing(CityDTO::getCityTitle)).toList();
            model.addAttribute("cities", citiesList);
            model.addAttribute("errorUrl", "Категории слетели из-за повтора филиала: обновите");
            return "redirect:/operators";
        }
        else if (companyService.save(companyDTO)) {
            log.info("OK.Начинаем сохранение компании прошло успешно");
//            for (Company company : companyService.getAllCompaniesList()) {
            log.info("вход в меняем статус с К рассылке на В работе");
            leadService.changeStatusLeadOnInWork(leadId);
            leadService.changeCountToOperator(leadId);
            log.info("статус успешно сменен К рассылке на В работе");
//                System.out.println(company);
//            }
            return "redirect:/operators";
        } else {
            log.info("ERROR.Начинаем сохранение компании прошло НЕ успешно");
            return "redirect:/operators";
        }
    } // Добавление новой компании из лида Менеджера


    @GetMapping("/new_company_to_manager/{leadId}")
    String newCompanyToManager(@PathVariable final Long leadId, Principal principal, Model model) { // Добавление новой компании из лида Менеджера
        model.addAttribute("newCompany", companyService.convertToDtoToManager(leadId, principal));
        List<CategoryDTO> categories = categoryService.getAllCategories().stream().sorted(Comparator.comparing(CategoryDTO::getCategoryTitle)).toList();
        model.addAttribute("categories", categories);
        List<CityDTO> citiesList = cityService.getAllCities().stream().sorted(Comparator.comparing(CityDTO::getCityTitle)).toList();
        model.addAttribute("cities", citiesList);
        model.addAttribute("leadId", leadId);
        return "companies/new_company_to_manager";
    } // Добавление новой компании из лида Менеджера

    @PostMapping("/new_company_to_manager")
    String saveNewCompanyToManager(Principal principal, @ModelAttribute("newCompany") @Valid CompanyDTO companyDTO, BindingResult bindingResult, @ModelAttribute("leadId") Long leadId, Model model) { // Добавление новой компании из лида Менеджера
        log.info("1.Начинаем сохранение компании");
        companyValidation.validate(companyDTO, bindingResult);
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            CompanyDTO companyDTO1 = companyService.convertToDtoToManager(leadId, principal);
            companyDTO.setUser(companyDTO1.getUser());
            model.addAttribute("newCompany", companyDTO);
            List<CategoryDTO> categories = categoryService.getAllCategories().stream().sorted(Comparator.comparing(CategoryDTO::getCategoryTitle)).toList();
            model.addAttribute("categories", categories);
            model.addAttribute("leadId", leadId);
            List<CityDTO> citiesList = cityService.getAllCities().stream().sorted(Comparator.comparing(CityDTO::getCityTitle)).toList();
            model.addAttribute("cities", citiesList);
            model.addAttribute("errorUrl", "Категории слетели из-за повтора филиала: обновите");
            return "companies/new_company_to_manager";
        }
        else if (companyService.save(companyDTO)) {
            log.info("OK.Начинаем сохранение компании прошло успешно");
//            for (Company company : companyService.getAllCompaniesList()) {
                log.info("вход в меняем статус с К рассылке на В работе");
                leadService.changeStatusLeadOnInWork(leadId);
                log.info("статус успешно сменен К рассылке на В работе");
//                System.out.println(company);
//            }
            return "redirect:/companies/company";
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


    @GetMapping("/add_company")
    String newCompany(Principal principal, Model model) { // Добавление новой компании из лида Менеджера
        model.addAttribute("newCompany", companyService.convertToDtoToManagerNotLead(principal));
        List<CategoryDTO> categories = categoryService.getAllCategories().stream().sorted(Comparator.comparing(CategoryDTO::getCategoryTitle)).toList();
        List<CityDTO> citiesList = cityService.getAllCities().stream().sorted(Comparator.comparing(CityDTO::getCityTitle)).toList();
        System.out.println(citiesList);
        model.addAttribute("categories", categories);
        model.addAttribute("cities", citiesList);
        return "companies/new_company";
    } // Добавление новой компании из лида Менеджера


    @PostMapping("/add_company")
    String addCompanyToManager(Principal principal, @ModelAttribute("newCompany") @Valid CompanyDTO companyDTO, BindingResult bindingResult, Model model) { // Добавление новой компании из лида Менеджера
        log.info("1.Начинаем сохранение компании");
        System.out.println(companyDTO.getFilial());
        companyValidation.validate(companyDTO, bindingResult);
        if (bindingResult.hasErrors()) {
            log.info("1.1 Вошли в ошибку");
            CompanyDTO companyDTO1 = companyService.convertToDtoToManagerNotLead(principal);
            companyDTO.setUser(companyDTO1.getUser());
            model.addAttribute("newCompany", companyDTO);
            List<CategoryDTO> categories = categoryService.getAllCategories().stream().sorted(Comparator.comparing(CategoryDTO::getCategoryTitle)).toList();
            model.addAttribute("categories", categories);
            List<CityDTO> citiesList = cityService.getAllCities().stream().sorted(Comparator.comparing(CityDTO::getCityTitle)).toList();
            model.addAttribute("cities", citiesList);
            model.addAttribute("errorUrl", "Категории слетели из-за повтора филиала: обновите");
            return "companies/new_company";
        }

        else if (companyService.save(companyDTO)) {
            log.info("OK.Начинаем сохранение компании прошло успешно");
//            for (Company company : companyService.getAllCompaniesList()) {
//                System.out.println(company);
//            }
            return "redirect:/companies/company";
        } else {
            log.info("ERROR.Начинаем сохранение компании прошло НЕ успешно");
            return "redirect:/lead";
        }
    } // Добавление новой компании из лида Менеджера
}



