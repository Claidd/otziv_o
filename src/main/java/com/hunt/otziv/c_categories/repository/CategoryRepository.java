package com.hunt.otziv.c_categories.repository;

import com.hunt.otziv.c_categories.model.Category;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface CategoryRepository extends CrudRepository<Category, Long> {

    @Override
    @Query("SELECT DISTINCT c FROM Category c LEFT JOIN FETCH c.subCategoryTitle sc")
    @EntityGraph(attributePaths = {"subCategoryTitle"})
    List<Category> findAll();
}
