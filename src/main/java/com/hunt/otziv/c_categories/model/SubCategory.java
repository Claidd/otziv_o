package com.hunt.otziv.c_categories.model;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.r_review.model.Review;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    private Category category;

    @OneToMany(mappedBy = "subCategory",fetch = FetchType.LAZY)
    @ToString.Exclude
    List<Company> companyList;

    @OneToMany(mappedBy = "subCategory", fetch = FetchType.LAZY)
    @ToString.Exclude
    List<Review> reviews;


}
