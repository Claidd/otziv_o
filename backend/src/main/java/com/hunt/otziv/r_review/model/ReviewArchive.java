package com.hunt.otziv.r_review.model;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.p_products.model.OrderDetails;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_archive_category")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Category category;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_archive_subcategory")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private SubCategory subCategory;

}
