package com.hunt.otziv.c_categories.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "product_categorys")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_category_id")
    private Long id;

    @Column(name = "product_category_title")
    private String categoryTitle;

    @OneToMany(mappedBy = "category",cascade = CascadeType.ALL)
    @Column(name = "product_subcategory_title")
    List<SubCategory> subCategoryTitle;

}
