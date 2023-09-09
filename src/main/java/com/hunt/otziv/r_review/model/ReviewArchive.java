package com.hunt.otziv.r_review.model;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.p_products.model.OrderDetails;
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
@Table(name = "reviews_archive")
public class ReviewArchive {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_archive_id")
    private Long id;
    @Column(name = "review_archive_text")
    private String text;
    @Column(name = "review_archive_answer")
    private String answer;
    @ManyToOne
    @JoinColumn(name = "review_archive_category")
    private Category category;
    @ManyToOne
    @JoinColumn(name = "review_archive_subcategory")
    private SubCategory subCategory;

}
