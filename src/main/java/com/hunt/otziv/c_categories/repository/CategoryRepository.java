package com.hunt.otziv.c_categories.repository;

import com.hunt.otziv.c_categories.model.Category;
import org.springframework.data.repository.CrudRepository;

public interface CategoryRepository extends CrudRepository<Category, Long> {
}
