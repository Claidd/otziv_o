package com.hunt.otziv.c_categories.model;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.r_review.model.Review;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "categorys")
@NamedEntityGraph(name = "Category.subCategoryTitle", attributeNodes = @NamedAttributeNode("subCategoryTitle"))
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long id;

    @Column(name = "category_title")
    private String categoryTitle;

    @OneToMany(mappedBy = "category",cascade = CascadeType.ALL)
//    @Column(name = "subcategory_title")
//    @BatchSize(size = 10)
//    @Fetch(FetchMode.JOIN)
    List<SubCategory> subCategoryTitle;

    @OneToMany(mappedBy = "categoryCompany",cascade = CascadeType.ALL)
    @ToString.Exclude
    List<Company> companyCategory;

    @OneToMany(mappedBy = "category",cascade = CascadeType.ALL)
    @ToString.Exclude
    List<Review> reviews;



}
