package com.hunt.otziv.bad_reviews.model;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "bad_review_tasks")
public class BadReviewTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bad_review_task_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bad_review_task_order")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bad_review_task_review")
    private Review sourceReview;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bad_review_task_worker")
    private Worker worker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bad_review_task_bot")
    private Bot bot;

    @Enumerated(EnumType.STRING)
    @Column(name = "bad_review_task_status", nullable = false)
    private BadReviewTaskStatus status;

    @Column(name = "bad_review_task_original_rating")
    private Integer originalRating;

    @Column(name = "bad_review_task_target_rating")
    private Integer targetRating;

    @Column(name = "bad_review_task_price")
    private BigDecimal price;

    @Column(name = "bad_review_task_scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "bad_review_task_completed_date")
    private LocalDate completedDate;

    @CreationTimestamp
    @Column(name = "bad_review_task_created")
    private LocalDate created;

    @UpdateTimestamp
    @Column(name = "bad_review_task_changed")
    private LocalDate changed;

    @Column(name = "bad_review_task_comment")
    private String comment;
}
