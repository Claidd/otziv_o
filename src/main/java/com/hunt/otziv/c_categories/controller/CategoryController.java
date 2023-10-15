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
    public String getAllCategories(Model model) {
        List<CategoryDTO> categories = categoryService.getAllCategories().stream().sorted(Comparator.comparing(CategoryDTO::getCategoryTitle)).toList();
        model.addAttribute("categories", categories);
        model.addAttribute("categoryDTO", new CategoryDTO());
        return "category/categories";
    }

    @GetMapping("/getSubcategories")
    @ResponseBody
    public List<SubCategoryDTO> getSubcategoriesByCategoryId(@RequestParam Long categoryId) {
        return subCategoryService.getSubcategoriesByCategoryId(categoryId).stream().sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle)).toList();
    }
    @PostMapping
    public String createCategory(@ModelAttribute("categoryDTO") CategoryDTO categoryDTO, RedirectAttributes rm) {
        categoryService.saveCategory(categoryDTO);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/categories";
    }

    @GetMapping("/update/{id}")
    public String editCategory(@PathVariable Long id, Model model) {
        model.addAttribute("categoryDTO", categoryService.getCategoryById(id));
        return "category/edit_categories";
    }
    @PostMapping("/update/{id}")
    public String updateCategory(@PathVariable Long id, @ModelAttribute("categoryDTO") CategoryDTO categoryDTO, RedirectAttributes rm) {
        categoryService.updateCategory(id, categoryDTO);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/categories";
    }

    @PostMapping("/delete/{id}")
    public String deleteCategory(@PathVariable Long id, RedirectAttributes rm) {
        log.info("входим в удаление");
        categoryService.deleteCategory(id);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/categories";
    }


}
