package com.hunt.otziv.c_categories.services;

import com.hunt.otziv.c_categories.dto.ProductCategoryDTO;
import com.hunt.otziv.c_categories.model.ProductCategory;
import com.hunt.otziv.c_categories.repository.ProductCategoryRepository;
import com.hunt.otziv.p_products.services.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductCategoryServiceImpl implements ProductCategoryService {

    private final ProductCategoryRepository productCategoryRepository;

    public List<ProductCategoryDTO> productCategoryDTOList(){ // Список всех категорий продукта
        return convertProductCategoryDTOList(productCategoryRepository.findAll());
    } // Список всех категорий продукта

    private List<ProductCategoryDTO> convertProductCategoryDTOList(List<ProductCategory> productCategories){ // Перевод категорий продукта в ДТО
        return productCategories.stream().map(this :: convertProductCategoryToDTO).collect(Collectors.toList());
    } // Перевод категорий продукта в ДТО

    private ProductCategoryDTO convertProductCategoryToDTO(ProductCategory productCategory){ // Перевод категорий продукта в ДТО
        return ProductCategoryDTO.builder()
                .id(productCategory.getId())
                .title(productCategory.getTitle())
                .build();
    } // Перевод категорий продукта в ДТО

    public ProductCategory findById(Long productCategoryId){ // Найти категорию продукта в ID
        return productCategoryRepository.findById(productCategoryId).orElse(null);
    } // Найти категорию продукта в ID

    public void save(ProductCategory productCategory){ // Сохранить новую категорию продукта
        productCategoryRepository.save(productCategory);
    } // Сохранить новую категорию продукта

}
