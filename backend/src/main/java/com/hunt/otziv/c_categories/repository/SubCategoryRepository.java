package com.hunt.otziv.c_categories.repository;

import com.hunt.otziv.c_categories.model.SubCategory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubCategoryRepository extends CrudRepository<SubCategory, Long> {


    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"category"})
    List<SubCategory> findAll();

    List<SubCategory> findAllByCategoryId(Long categoryId);
}
