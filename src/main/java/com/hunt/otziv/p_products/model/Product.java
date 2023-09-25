package com.hunt.otziv.p_products.model;

import com.hunt.otziv.c_categories.model.ProductCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    @Column(name = "product_title")
    private String title;

    @Column(name = "product_price")
    private BigDecimal price;

    @ManyToOne
    @JoinColumn(name = "product_category")
    @ToString.Include
    private ProductCategory productCategory;
}
