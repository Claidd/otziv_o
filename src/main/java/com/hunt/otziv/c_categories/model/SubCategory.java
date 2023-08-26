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
@Table(name = "subcategoryes")
public class SubCategory {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subcategory_id")
    private Long id;

    @Column(name = "subcategory_title")
    private String subCategoryTitle;

    @ManyToOne
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    private Category category;

    @OneToMany(mappedBy = "subCategory",cascade = CascadeType.ALL)
    @ToString.Exclude
    List<Company> companyList;
}
