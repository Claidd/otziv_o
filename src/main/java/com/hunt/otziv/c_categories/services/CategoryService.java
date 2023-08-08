package com.hunt.otziv.c_categories.services;

import com.hunt.otziv.c_categories.dto.CategoryDTO;

import java.util.List;

public interface CategoryService {
    // Создание новой категории
    CategoryDTO saveCategory(CategoryDTO categoryDTO);
    // Обновить категорию
    CategoryDTO updateCategory(Long categoryId, CategoryDTO categoryDTO);
    // Удалить категорию
    void deleteCategory(Long id);
    // Найти все категории
    List<CategoryDTO> getAllCategories();
    // Найти категорию по id
    CategoryDTO getCategoryById(Long categoryId);
}
