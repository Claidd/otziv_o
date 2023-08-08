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
    public SubCategoryDTO saveSubCategory(Long categoryId, SubCategoryDTO subCategoryDTO) {
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
    }

    @Override
    public SubCategoryDTO updateSubCategory(Long subCategoryId, SubCategoryDTO subCategoryDTO) {
        SubCategory subCategory = subCategoryRepository.findById(subCategoryId).orElse(null);
        if (subCategory == null) {
            // Handle subcategory not found case
            return null;
        }

        subCategory.setSubCategoryTitle(subCategoryDTO.getSubCategoryTitle());

        SubCategory updatedSubCategory = subCategoryRepository.save(subCategory);
        return new SubCategoryDTO(updatedSubCategory.getId(), updatedSubCategory.getSubCategoryTitle());
    }

    @Override
    public void deleteSubCategory(Long subCategoryId) {
        subCategoryRepository.deleteById(subCategoryId);
    }

    @Override
    public List<SubCategoryDTO> getAllSubCategories() {
        List<SubCategory> subCategories = (List<SubCategory>) subCategoryRepository.findAll();
        return subCategories.stream()
                .map(this::convertToSubCategoryDTO)
                .sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle))
                .collect(Collectors.toList());
    }

    // Метод для преобразования сущности SubCategory в DTO SubCategoryDTO
    private SubCategoryDTO convertToSubCategoryDTO(SubCategory subCategory) {
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId());
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle());
        // Здесь можете заполнить другие поля DTO, если они есть

        return subCategoryDTO;
    }

    @Override
    public List<SubCategoryDTO> getSubcategoriesByCategoryId(Long categoryId) {
        List<SubCategory> subCategories = subCategoryRepository.findAllByCategory(categoryId);
        return subCategories.stream()
                .map(this::convertToSubCategoryDTO)
                .collect(Collectors.toList());
    }

    public SubCategoryDTO getCategoryById(Long categoryId) {
        Optional<SubCategory> categoryOptional = subCategoryRepository.findById(categoryId);
        return categoryOptional.map(this::convertToSubCategoryDTO).orElse(null);
    }

}
