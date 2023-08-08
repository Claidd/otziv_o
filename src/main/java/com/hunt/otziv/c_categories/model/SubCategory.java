package com.hunt.otziv.c_categories.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "product_subcategoryes")
public class SubCategory {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_subcategory_id")
    private Long id;


    @Column(name = "product_subcategory_title")
    private String subCategoryTitle;

    @ManyToOne
    @JoinColumn(name = "product_category_id")
    private Category category;
}
