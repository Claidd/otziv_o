package com.hunt.otziv.c_categories.services;

import com.hunt.otziv.c_categories.dto.ProductCategoryDTO;
import com.hunt.otziv.c_categories.model.ProductCategory;

import java.util.List;

public interface ProductCategoryService{

    List<ProductCategoryDTO> productCategoryDTOList();
    ProductCategory findById(Long productCategoryId);
    void save(ProductCategory productCategory);

}
