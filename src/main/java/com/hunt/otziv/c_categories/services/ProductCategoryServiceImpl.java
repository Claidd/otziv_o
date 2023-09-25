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

    public List<ProductCategoryDTO> productCategoryDTOList(){
        return convertProductCategoryDTOList(productCategoryRepository.findAll());
    }

    private List<ProductCategoryDTO> convertProductCategoryDTOList(List<ProductCategory> productCategories){
        return productCategories.stream().map(this :: convertProductCategoryToDTO).collect(Collectors.toList());
    }

    private ProductCategoryDTO convertProductCategoryToDTO(ProductCategory productCategory){
        return ProductCategoryDTO.builder()
                .id(productCategory.getId())
                .title(productCategory.getTitle())
                .build();
    }

    public ProductCategory findById(Long productCategoryId){
        return productCategoryRepository.findById(productCategoryId).orElse(null);
    }

    public void save(ProductCategory productCategory){
        productCategoryRepository.save(productCategory);
    }

}
