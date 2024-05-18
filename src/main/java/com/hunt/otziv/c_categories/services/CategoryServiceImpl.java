package com.hunt.otziv.c_categories.services;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.repository.CategoryRepository;
import com.hunt.otziv.c_categories.repository.SubCategoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
@Service
@Slf4j
public class CategoryServiceImpl implements CategoryService{
    private final CategoryRepository categoryRepository;
    private final SubCategoryRepository subCategoryRepository;

    @Autowired
    public CategoryServiceImpl(CategoryRepository categoryRepository, SubCategoryRepository subCategoryRepository) {
        this.categoryRepository = categoryRepository;
        this.subCategoryRepository = subCategoryRepository;
    }

    @Override
    public CategoryDTO saveCategory(CategoryDTO categoryDTO) { // Лист категорий
        Category category = new Category();
        category.setCategoryTitle(categoryDTO.getCategoryTitle());
        // Convert SubCategoryDTO list to SubCategory entities
        List<SubCategory> subCategories = categoryDTO.getSubCategories().stream()
                .map(subCategoryDTO -> {
                    SubCategory subCategory = new SubCategory();
                    subCategory.setSubCategoryTitle(subCategoryDTO.getSubCategoryTitle());
                    subCategory.setCategory(category);
                    return subCategory;
                })
                .collect(Collectors.toList());
        category.setSubCategoryTitle(subCategories);
        Category savedCategory = categoryRepository.save(category);
        return convertToDTO(savedCategory);
    } // Лист категорий

    @Override
    public CategoryDTO updateCategory(Long categoryId, CategoryDTO categoryDTO) { // Обновление категорий
        Category category = categoryRepository.findById(categoryId).orElse(null);
        if (category == null) {
            // Handle not found case
            return null;
        }

        category.setCategoryTitle(categoryDTO.getCategoryTitle());
        // Update SubCategory entities
        List<SubCategory> updatedSubCategories = category.getSubCategoryTitle().stream()
                .filter(subCategory -> {
                    for (SubCategoryDTO subCategoryDTO : categoryDTO.getSubCategories()) {
                        if (subCategory.getId().equals(subCategoryDTO.getId())) {
                            subCategory.setSubCategoryTitle(subCategoryDTO.getSubCategoryTitle());
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());
        category.setSubCategoryTitle(updatedSubCategories);
        Category updatedCategory = categoryRepository.save(category);
        return convertToDTO(updatedCategory);
    } // Обновление категорий

    @Override
    public void deleteCategory(Long id) {
        categoryRepository.deleteById(id);
    } // Удаление категорий

    @Override
    @Transactional
    public List<CategoryDTO> getAllCategories() { // Взять все категории
        long startTime = System.nanoTime();
        List<Category> categories = categoryRepository.findAllCategoryAndSubcategory();
        List<CategoryDTO> categoriesDto = categories.stream()
                .map(this::convertToDTO)
                .sorted(Comparator.comparing(CategoryDTO::getCategoryTitle))
                .toList();
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf("Список категорий: %.4f сек%n", timeElapsed);
        return categoriesDto;
    } // Взять все категории


    private CategoryDTO convertToDTO(Category category) { // Перевод категории в ДТО
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        List<SubCategoryDTO> subCategoryDTOList = category.getSubCategoryTitle().stream()
                .map(subCategory -> new SubCategoryDTO(subCategory.getId(), subCategory.getSubCategoryTitle(), null))
                .sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle)) // Сортировка подкатегорий
                .collect(Collectors.toList());
        categoryDTO.setSubCategories(subCategoryDTOList);
        return categoryDTO;
    } // Перевод категории в ДТО

    public CategoryDTO getCategoryById(Long categoryId) { // Взять категорию ДТО по Id
        Optional<Category> categoryOptional = categoryRepository.findById(categoryId);
        return categoryOptional.map(this::convertToDTO).orElse(null);
    } // Взять категорию ДТО по Id

    public Category getCategoryByIdCategory(Long categoryId) { // Взять категорию по Id
       return categoryRepository.findById(categoryId).orElse(null);
    } // Взять категорию по Id

}
