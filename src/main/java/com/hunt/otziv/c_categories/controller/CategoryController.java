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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
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
    public String getAllCategories(Model model, @RequestParam(defaultValue = "") String keyword) { // Лист всех категорий
        if (keyword.isEmpty()) {
            List<CategoryDTO> categories = categoryService.getAllCategories();
            model.addAttribute("categories", categories);
            model.addAttribute("categoryDTO", new CategoryDTO());
        } else {
            List<CategoryDTO> categories = categoryService.getAllCategoriesKeywords(keyword);
            model.addAttribute("categories", categories);
            model.addAttribute("categoryDTO", new CategoryDTO());
        }
        return "category/categories";
    } // Лист всех категорий

    @GetMapping("/getSubcategories")
    @ResponseBody
    public List<SubCategoryDTO> getSubcategoriesByCategoryId(@RequestParam Long categoryId) { // Подгрузка субкатегорий
        return subCategoryService.getSubcategoriesByCategoryId(categoryId).stream().sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle)).toList();
    } // Подгрузка субкатегорий

    @PostMapping
    public String createCategory(@ModelAttribute("categoryDTO") CategoryDTO categoryDTO, RedirectAttributes rm) { // Создание новой категории
        categoryService.saveCategory(categoryDTO);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/categories";
    } // Создание новой категории

    @GetMapping("/update/{id}")
    public String editCategory(@PathVariable Long id, Model model) { // Обновление категории
        model.addAttribute("categoryDTO", categoryService.getCategoryById(id));
        return "category/edit_categories";
    } // Обновление категории
    @PostMapping("/update/{id}")
    public String updateCategory(@PathVariable Long id, @ModelAttribute("categoryDTO") CategoryDTO categoryDTO, RedirectAttributes rm) { // Обновление категории
        categoryService.updateCategory(id, categoryDTO);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/categories";
    } // Обновление категории

    @PostMapping("/delete/{id}")
    public String deleteCategory(@PathVariable Long id, RedirectAttributes rm) { // Удаление категории
        log.info("входим в удаление");
        categoryService.deleteCategory(id);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/categories";
    } // Удаление категории


}
