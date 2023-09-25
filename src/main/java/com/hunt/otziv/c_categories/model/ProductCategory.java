package com.hunt.otziv.c_categories.model;

import com.hunt.otziv.p_products.model.Product;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.Set;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "product_categorys")
public class ProductCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_category_id")
    private Long id;
    @Column(name = "product_category_title")
    private String title;

    @OneToMany(mappedBy = "productCategory", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Product> product;

    @Override
    public String toString() {
        return "ProductCategory{" +
                "id=" + id +
                ", title='" + title + '\'' +
                '}';
    }
}
