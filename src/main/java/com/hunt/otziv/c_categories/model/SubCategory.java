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
@NamedEntityGraph(name = "Subcategory.category", attributeNodes = @NamedAttributeNode("category"))
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
    @Fetch(FetchMode.JOIN)
    private Category category;

    @OneToMany(mappedBy = "subCategory",cascade = CascadeType.ALL)
    @ToString.Exclude
    List<Company> companyList;

    @OneToMany(mappedBy = "subCategory", cascade = CascadeType.ALL)
    @ToString.Exclude
    List<Review> reviews;


}
