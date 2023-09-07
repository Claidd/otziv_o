package com.hunt.otziv.p_products.model;

import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.r_review.model.Review;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "order_details")
public class OrderDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_detail_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_detail_order")
    private Order order;

    @OneToMany(mappedBy = "orderDetails",cascade = CascadeType.ALL)
    List<Review> reviews;

    @UpdateTimestamp
    @Column(name = "order_detail_date_published")
    private LocalDate publishedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_detail_product")
    private Product product;

    @Column(name = "order_detail_amount")
    private int amount;

    @Column(name = "order_detail_price")
    private BigDecimal price;
}
