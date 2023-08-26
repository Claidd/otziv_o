package com.hunt.otziv.c_categories.model;

import com.hunt.otziv.c_companies.model.Company;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "categorys")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long id;

    @Column(name = "category_title")
    private String categoryTitle;

    @OneToMany(mappedBy = "category",cascade = CascadeType.ALL)
    @Column(name = "subcategory_title")
    List<SubCategory> subCategoryTitle;

    @OneToMany(mappedBy = "categoryCompany",cascade = CascadeType.ALL)
    @ToString.Exclude
    List<Company> companyCategory;

}
