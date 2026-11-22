package com.hunt.otziv.c_categories.repository;

import com.hunt.otziv.c_categories.model.Category;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

@Repository
public interface CategoryRepository extends CrudRepository<Category, Long> {



    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.subCategoryTitle sc")
    List<Category> findAllCategoryAndSubcategory();

    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.subCategoryTitle sc WHERE LOWER(c.categoryTitle) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(sc.subCategoryTitle) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Category> findAllCategoryAndSubcategoryByKeywords(String keyword);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Category c WHERE c.id = :id")
    Optional<Category> findLockedById(@Param("id") Long id);

    /**
     * Companies and reviews (live, archived, kept for reuse, waiting in a recovery task) that point at the category or
     * at one of its subcategories. Plain SQL keeps the dictionary module free of references to their entities.
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM companies WHERE company_category = :id
                        OR company_subcategory IN (SELECT subcategory_id FROM subcategoryes WHERE category_id = :id))
                 + (SELECT COUNT(*) FROM reviews WHERE review_category = :id
                        OR review_subcategory IN (SELECT subcategory_id FROM subcategoryes WHERE category_id = :id))
                 + (SELECT COUNT(*) FROM archive_reviews WHERE review_category = :id
                        OR review_subcategory IN (SELECT subcategory_id FROM subcategoryes WHERE category_id = :id))
                 + (SELECT COUNT(*) FROM reviews_archive WHERE review_archive_category = :id
                        OR review_archive_subcategory IN (SELECT subcategory_id FROM subcategoryes WHERE category_id = :id))
                 + (SELECT COUNT(*) FROM review_recovery_tasks WHERE review_recovery_task_archive_category = :id
                        OR review_recovery_task_archive_subcategory IN (SELECT subcategory_id FROM subcategoryes WHERE category_id = :id))
            """, nativeQuery = true)
    long countUsagesByCategoryId(@Param("id") Long id);

    String CATEGORY_IN_USE = "Категория используется в компаниях или отзывах. Сначала переведите их в другую категорию.";

    /**
     * Deletes an unused category with its unused subcategories; a used one is refused (409), see
     * {@link #countUsagesByCategoryId}. Call inside a transaction: the row lock keeps a company from taking the category
     * between the count and the delete.
     */
    default void deleteUnusedCategory(Long id) {
        findLockedById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Категория не найдена"));
        if (countUsagesByCategoryId(id) > 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, CATEGORY_IN_USE);
        }
        deleteById(id);
    }


//    @Query("SELECT c, sc.id, sc.subCategoryTitle FROM Category c LEFT JOIN FETCH c.subCategoryTitle sc GROUP BY c.categoryTitle")
}
