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
import java.util.Objects;

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

//    @Id
//    @GeneratedValue(strategy = GenerationType.UUID)
//    @Column(name = "order_detail_id")
//    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_detail_order")
    private Order order;

    @OneToMany(mappedBy = "orderDetails",cascade = CascadeType.ALL)
    List<Review> reviews;

//    @OneToOne(mappedBy = "orderDetails", cascade = CascadeType.ALL)
//    Review review;

    @UpdateTimestamp
    @Column(name = "order_detail_date_published")
    private LocalDate publishedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_detail_product")
    private Product product;

    @Column(name = "order_detail_amount")
    private int amount;

    @Column(name = "order_detail_comments")
    private String comment;

    @Column(name = "order_detail_price")
    private BigDecimal price;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrderDetails that = (OrderDetails) o;
        return amount == that.amount && Objects.equals(id, that.id) && Objects.equals(reviews, that.reviews) && Objects.equals(publishedDate, that.publishedDate) && Objects.equals(product, that.product) && Objects.equals(price, that.price);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, reviews, publishedDate, product, amount, price);
    }
}
