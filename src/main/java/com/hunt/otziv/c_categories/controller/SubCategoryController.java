package com.hunt.otziv.c_categories.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

@Controller
@RequestMapping("/subcategories")
public class SubCategoryController {

    private final SubCategoryService subCategoryService;
    private final CategoryService categoryService;

    @Autowired
    public SubCategoryController(SubCategoryService subCategoryService, CategoryService categoryService) {
        this.subCategoryService = subCategoryService;
        this.categoryService = categoryService;
    }

    @GetMapping("/{id}/{categoryTitle}")
    public String showAllSubCategories(@PathVariable Long id,@PathVariable String categoryTitle, Model model) {
        System.out.println(categoryTitle);
        List<SubCategoryDTO> subCategories = subCategoryService.getSubcategoriesByCategoryId(id).stream().sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle)).toList();
        model.addAttribute("subCategories", subCategories);
        model.addAttribute("subCategoryDTO", new SubCategoryDTO());
        model.addAttribute("categoryId", id);
        model.addAttribute("categoryTitle", categoryTitle);
        return "category/subcategories";
    }

    @PostMapping("/{id}/{categoryTitle}")
    public String createSubCategory(@PathVariable Long id, @ModelAttribute("subCategoryDTO") SubCategoryDTO subCategoryDTO) {
        CategoryDTO categoryDTO = categoryService.getCategoryById(id);
        if (categoryDTO != null) {
//            subCategoryDTO.setCategory(categoryDTO);
            subCategoryService.saveSubCategory(id, subCategoryDTO);
        }
        return "redirect:/subcategories/{id}/{categoryTitle}";
    }

    @GetMapping("/update/{categoryId}/{categoryTitle}/{id}")
    public String editSubCategory(@PathVariable Long id, @PathVariable String categoryTitle, Model model) {
        model.addAttribute("subCategoryDTO", subCategoryService.getCategoryById(id));
        return "category/edit_subcategories";
    }
    @PostMapping("/update/{categoryId}/{categoryTitle}/{id}")
    public String updateSubCategory(@PathVariable Long id, @PathVariable String categoryTitle, @ModelAttribute("subCategoryDTO") SubCategoryDTO subCategoryDTO) {
        subCategoryService.updateSubCategory(id, subCategoryDTO);
        return "redirect:/subcategories/{categoryId}/{categoryTitle}";
    }

    @PostMapping("/delete/{categoryId}/{categoryTitle}/{id}")
    public String deleteSubCategory(@PathVariable Long id) {
        subCategoryService.deleteSubCategory(id);
        return "redirect:/subcategories/{categoryId}/{categoryTitle}";
    }

    @GetMapping()
    public String getAllSubCategories(Model model) {
        List<SubCategoryDTO> subCategories = subCategoryService.getAllSubCategories();
        model.addAttribute("subCategories", subCategories);
        model.addAttribute("subCategoryDTO", new SubCategoryDTO());
        return "category/subcategories";
    }
}
