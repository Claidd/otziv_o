package com.hunt.otziv.c_categories.services;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.repository.CategoryRepository;
import com.hunt.otziv.c_categories.repository.SubCategoryRepository;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SubCategoryServiceImpl implements SubCategoryService{
    private final SubCategoryRepository subCategoryRepository;
    private final CategoryRepository categoryRepository;

    @Autowired
    public SubCategoryServiceImpl(SubCategoryRepository subCategoryRepository, CategoryRepository categoryRepository) {
        this.subCategoryRepository = subCategoryRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public SubCategoryDTO saveSubCategory(Long categoryId, SubCategoryDTO subCategoryDTO) { // Сохранить новую подкатегорию
        Category category = categoryRepository.findById(categoryId).orElse(null);
        if (category == null) {
            // Handle category not found case
            return null;
        }
        SubCategory subCategory = new SubCategory();
        subCategory.setSubCategoryTitle(subCategoryDTO.getSubCategoryTitle());
        subCategory.setCategory(category);

        SubCategory savedSubCategory = subCategoryRepository.save(subCategory);
        return new SubCategoryDTO(savedSubCategory.getId(), savedSubCategory.getSubCategoryTitle());
    } // Сохранить новую подкатегорию

    @Override
    public SubCategoryDTO updateSubCategory(Long subCategoryId, SubCategoryDTO subCategoryDTO) { // Обновить подкатегорию
        SubCategory subCategory = subCategoryRepository.findById(subCategoryId).orElse(null);
        if (subCategory == null) {
            // Handle subcategory not found case
            return null;
        }

        subCategory.setSubCategoryTitle(subCategoryDTO.getSubCategoryTitle());

        SubCategory updatedSubCategory = subCategoryRepository.save(subCategory);
        return new SubCategoryDTO(updatedSubCategory.getId(), updatedSubCategory.getSubCategoryTitle());
    } // Обновить подкатегорию

    @Override
    public void deleteSubCategory(Long subCategoryId) { // Удалить подкатегорию
        subCategoryRepository.deleteById(subCategoryId);
    } // Удалить подкатегорию

    @Override
    public List<SubCategoryDTO> getAllSubCategories() { // Взять все подкатегории
        List<SubCategory> subCategories = (List<SubCategory>) subCategoryRepository.findAll();
        return subCategories.stream()
                .map(this::convertToSubCategoryDTO)
                .sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle))
                .collect(Collectors.toList());
    } // Взять все подкатегории

    // Метод для преобразования сущности SubCategory в DTO SubCategoryDTO
    private SubCategoryDTO convertToSubCategoryDTO(SubCategory subCategory) {
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId() != null ? subCategory.getId() : 0);
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle() != null ? subCategory.getSubCategoryTitle() : "Не Выбрано");
        // Здесь можете заполнить другие поля DTO, если они есть

        return subCategoryDTO;
    } // Метод для преобразования сущности SubCategory в DTO SubCategoryDTO

    @Override
    public List<SubCategoryDTO> getSubcategoriesByCategoryId(Long categoryId) { // Перевод подкатегории в ДТО
        List<SubCategory> subCategories = subCategoryRepository.findAllByCategoryId(categoryId);
        return subCategories.stream()
                .map(this::convertToSubCategoryDTO)
                .collect(Collectors.toList());
    } // Перевод подкатегории в ДТО

    public SubCategoryDTO getCategoryById(Long categoryId) { // Перевод подкатегории в ДТО
        Optional<SubCategory> categoryOptional = subCategoryRepository.findById(categoryId);
        return categoryOptional.map(this::convertToSubCategoryDTO).orElse(null);
    } // Перевод подкатегории в ДТО

    public SubCategory getCategoryByIdSubCategory(Long categoryId) { // Взять категорию по id подкатегории
        return subCategoryRepository.findById(categoryId).orElse(null);
    } // Взять категорию по id подкатегории

    public SubCategory getSubCategoryById(Long subCategoryId) { // Взять подкатегорию по id
        return subCategoryRepository.findById(subCategoryId).orElse(null);
    } // Взять подкатегорию по id

}
