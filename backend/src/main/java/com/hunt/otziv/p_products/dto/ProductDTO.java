package com.hunt.otziv.p_products.dto;

import com.hunt.otziv.c_categories.model.ProductCategory;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDTO {
    private Long id;

    private String title;

    private BigDecimal price;

    private ProductCategory productCategory;

    private boolean photo;
}
