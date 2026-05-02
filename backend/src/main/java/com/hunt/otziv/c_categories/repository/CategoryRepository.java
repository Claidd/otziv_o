package com.hunt.otziv.c_categories.repository;

import com.hunt.otziv.c_categories.model.Category;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface CategoryRepository extends CrudRepository<Category, Long> {



    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.subCategoryTitle sc")
    List<Category> findAllCategoryAndSubcategory();

    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.subCategoryTitle sc WHERE LOWER(c.categoryTitle) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(sc.subCategoryTitle) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Category> findAllCategoryAndSubcategoryByKeywords(String keyword);


//    @Query("SELECT c, sc.id, sc.subCategoryTitle FROM Category c LEFT JOIN FETCH c.subCategoryTitle sc GROUP BY c.categoryTitle")
}
