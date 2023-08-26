package com.hunt.otziv.c_categories.services;

import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.SubCategory;

import java.util.List;

public interface SubCategoryService {
    // Создание новой субкатегории
    SubCategoryDTO saveSubCategory(Long categoryId, SubCategoryDTO subCategoryDTO);
    // Обновить субкатегорию
    SubCategoryDTO updateSubCategory(Long subCategoryId, SubCategoryDTO subCategoryDTO);
    // Удалить субкатегорию
    void deleteSubCategory(Long subCategoryId);
    // Найти все субкатегории
    List<SubCategoryDTO> getAllSubCategories();
    List<SubCategoryDTO> getSubcategoriesByCategoryId(Long categoryId);
    SubCategoryDTO getCategoryById(Long categoryId);

    SubCategory getCategoryByIdSubCategory(Long categoryId);
}
