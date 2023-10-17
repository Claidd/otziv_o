package com.hunt.otziv.c_categories.repository;

import com.hunt.otziv.c_categories.model.SubCategory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface SubCategoryRepository extends CrudRepository<SubCategory, Long> {

    List<SubCategory> findAll();
    @Query("SELECT DISTINCT s FROM SubCategory s LEFT JOIN FETCH s.category c WHERE c.id = :categoryId")
    @EntityGraph(attributePaths = {"category"})
    List<SubCategory> findAllByCategoryId(Long categoryId);
}
