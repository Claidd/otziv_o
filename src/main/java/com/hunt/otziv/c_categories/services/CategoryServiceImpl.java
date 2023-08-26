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
    public CategoryDTO saveCategory(CategoryDTO categoryDTO) {
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
    }

    @Override
    public CategoryDTO updateCategory(Long categoryId, CategoryDTO categoryDTO) {
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
    }

    @Override
    public void deleteCategory(Long id) {
        categoryRepository.deleteById(id);
    }

    @Override
    public List<CategoryDTO> getAllCategories() {
        List<Category> categories = (List<Category>) categoryRepository.findAll();
        return categories.stream()
                .map(this::convertToDTO)
                .sorted(Comparator.comparing(CategoryDTO::getCategoryTitle))
                .collect(Collectors.toList());
    }

    private CategoryDTO convertToDTO(Category category) {
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        List<SubCategoryDTO> subCategoryDTOList = subCategoryRepository.findAllByCategory(category.getId()).stream()
                .map(subCategory -> new SubCategoryDTO(subCategory.getId(), subCategory.getSubCategoryTitle(), null))
                .collect(Collectors.toList());
        categoryDTO.setSubCategories(subCategoryDTOList);

        return categoryDTO;
    }

//    private CategoryDTO convertToDTO(Category category) {
//        CategoryDTO categoryDTO = new CategoryDTO();
//        categoryDTO.setId(category.getId());
//        categoryDTO.setCategoryTitle(category.getCategoryTitle());
//        List<SubCategoryDTO> subCategoryDTOList = category.getSubCategoryTitle().stream()
//                .map(subCategory -> new SubCategoryDTO(subCategory.getId(), subCategory.getSubCategoryTitle()))
//                .collect(Collectors.toList());
//        categoryDTO.setSubCategories(subCategoryDTOList);
//        return categoryDTO;
//    }

    public CategoryDTO getCategoryById(Long categoryId) {
        Optional<Category> categoryOptional = categoryRepository.findById(categoryId);
        return categoryOptional.map(this::convertToDTO).orElse(null);
    }

    public Category getCategoryByIdCategory(Long categoryId) {
       return categoryRepository.findById(categoryId).orElse(null);

    }


//    private CategoryDTO convertToCategoryDTO(Category category) {
//        CategoryDTO categoryDTO = new CategoryDTO();
//        categoryDTO.setId(category.getId());
//        categoryDTO.setCategoryTitle(category.getCategoryTitle());

        // Если в классе Category есть поле List<SubCategory> subCategoryTitle,
        // которое нужно скопировать в CategoryDTO, выполните следующие действия:
        // List<SubCategoryDTO> subCategoryDTOList = category.getSubCategoryTitle().stream()
        //         .map(this::convertToSubCategoryDTO)
        //         .collect(Collectors.toList());
        // categoryDTO.setSubCategoryTitle(subCategoryDTOList);

//        return categoryDTO;
//    }
}
