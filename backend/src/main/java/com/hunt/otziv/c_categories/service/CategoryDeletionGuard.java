package com.hunt.otziv.c_categories.service;

import com.hunt.otziv.c_categories.repository.CategoryRepository;
import com.hunt.otziv.c_categories.repository.SubCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletion of a category or subcategory from the old pages, with the same rule as the dictionary page: a category used
 * by a company or by a review — live, archived, kept for reuse or waiting in a recovery task — is refused, because
 * deleting it would silently drop the category of those records. See {@link CategoryRepository#deleteUnusedCategory}.
 */
@Service
@RequiredArgsConstructor
public class CategoryDeletionGuard {

    private final CategoryRepository categoryRepository;
    private final SubCategoryRepository subCategoryRepository;

    @Transactional
    public void deleteCategory(Long id) {
        categoryRepository.deleteUnusedCategory(id);
    }

    @Transactional
    public void deleteSubCategory(Long id) {
        subCategoryRepository.deleteUnusedSubCategory(id);
    }
}
