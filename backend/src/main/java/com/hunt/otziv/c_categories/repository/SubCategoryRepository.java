package com.hunt.otziv.c_categories.repository;

import com.hunt.otziv.c_categories.model.SubCategory;
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
public interface SubCategoryRepository extends CrudRepository<SubCategory, Long> {


    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"category"})
    List<SubCategory> findAll();

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"category"})
    List<SubCategory> findAllByCategoryId(Long categoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SubCategory s WHERE s.id = :id")
    Optional<SubCategory> findLockedById(@Param("id") Long id);

    /** Companies and reviews (live, archived, kept for reuse, waiting in a recovery task) that point at the subcategory. */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM companies WHERE company_subcategory = :id)
                 + (SELECT COUNT(*) FROM reviews WHERE review_subcategory = :id)
                 + (SELECT COUNT(*) FROM archive_reviews WHERE review_subcategory = :id)
                 + (SELECT COUNT(*) FROM reviews_archive WHERE review_archive_subcategory = :id)
                 + (SELECT COUNT(*) FROM review_recovery_tasks WHERE review_recovery_task_archive_subcategory = :id)
            """, nativeQuery = true)
    long countUsagesBySubCategoryId(@Param("id") Long id);

    String SUBCATEGORY_IN_USE = "Подкатегория используется в компаниях или отзывах. Сначала переведите их в другую подкатегорию.";

    /** Deletes an unused subcategory; a used one is refused (409). Call inside a transaction (row lock). */
    default void deleteUnusedSubCategory(Long id) {
        findLockedById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Подкатегория не найдена"));
        if (countUsagesBySubCategoryId(id) > 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, SUBCATEGORY_IN_USE);
        }
        deleteById(id);
    }
}
