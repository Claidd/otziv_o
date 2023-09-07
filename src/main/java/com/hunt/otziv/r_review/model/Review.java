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
@Table(name = "reviews")
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;
    @Column(name = "review_text")
    private String text;
    @Column(name = "review_answer")
    private String answer;
    @ManyToOne
    @JoinColumn(name = "review_category")
    private Category category;
    @ManyToOne
    @JoinColumn(name = "review_subcategory")
    private SubCategory subCategory;
    @ManyToOne
    @JoinColumn(name = "review_bot")
    private Bot bot;
    @ManyToOne
    @JoinColumn(name = "review_order_details")
    private OrderDetails orderDetails;

}
