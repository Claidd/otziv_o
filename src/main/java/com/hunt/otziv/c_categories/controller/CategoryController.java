package com.hunt.otziv.c_categories.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@Slf4j
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;

    @Autowired
    public CategoryController(CategoryService categoryService, SubCategoryService subCategoryService) {
        this.categoryService = categoryService;
        this.subCategoryService = subCategoryService;
    }


    @GetMapping
    public String getAllCategories(Model model) {
        List<CategoryDTO> categories = categoryService.getAllCategories();
        model.addAttribute("categories", categories);
        model.addAttribute("categoryDTO", new CategoryDTO());
        return "category/categories";
    }

    @GetMapping("/getSubcategories")
    @ResponseBody
    public List<SubCategoryDTO> getSubcategoriesByCategoryId(@RequestParam Long categoryId) {
        return subCategoryService.getSubcategoriesByCategoryId(categoryId);
    }
    @PostMapping
    public String createCategory(@ModelAttribute("categoryDTO") CategoryDTO categoryDTO) {
        categoryService.saveCategory(categoryDTO);
        return "redirect:/categories";
    }

    @GetMapping("/update/{id}")
    public String editCategory(@PathVariable Long id, Model model) {
        model.addAttribute("categoryDTO", categoryService.getCategoryById(id));
        return "category/edit_categories";
    }
    @PostMapping("/update/{id}")
    public String updateCategory(@PathVariable Long id, @ModelAttribute("categoryDTO") CategoryDTO categoryDTO) {
        categoryService.updateCategory(id, categoryDTO);
        return "redirect:/categories";
    }

    @PostMapping("/delete/{id}")
    public String deleteCategory(@PathVariable Long id) {
        log.info("входим в удаление");
        categoryService.deleteCategory(id);
        return "redirect:/categories";
    }


}
