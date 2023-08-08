package com.hunt.otziv.c_categories.repository;

import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface SubCategoryRepository extends CrudRepository<SubCategory, Long> {

    List<SubCategory> findAll();
    @Query("SELECT s FROM SubCategory s WHERE s.category.id = :id")
    List<SubCategory> findAllByCategory(Long id);
}
