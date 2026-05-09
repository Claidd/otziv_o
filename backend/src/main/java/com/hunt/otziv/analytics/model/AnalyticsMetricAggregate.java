package com.hunt.otziv.analytics.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@MappedSuperclass
public abstract class AnalyticsMetricAggregate {

    @Column(name = "salary_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal salarySum = BigDecimal.ZERO;

    @Column(name = "salary_entry_count", nullable = false)
    private long salaryEntryCount;

    @Column(name = "salary_review_count", nullable = false)
    private long salaryReviewCount;

    @Column(name = "payment_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal paymentSum = BigDecimal.ZERO;

    @Column(name = "payment_count", nullable = false)
    private long paymentCount;

    @Column(name = "new_companies_count", nullable = false)
    private long newCompaniesCount;

    @Column(name = "new_orders_count", nullable = false)
    private long newOrdersCount;

    @Column(name = "correction_orders_count", nullable = false)
    private long correctionOrdersCount;

    @Column(name = "published_reviews_count", nullable = false)
    private long publishedReviewsCount;

    @Column(name = "reviews_to_publish_count", nullable = false)
    private long reviewsToPublishCount;

    @Column(name = "reviews_to_walk_count", nullable = false)
    private long reviewsToWalkCount;

    @Column(name = "leads_new_count", nullable = false)
    private long leadsNewCount;

    @Column(name = "leads_in_work_count", nullable = false)
    private long leadsInWorkCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        normalizeNumbers();
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        normalizeNumbers();
        updatedAt = Instant.now();
    }

    private void normalizeNumbers() {
        if (salarySum == null) {
            salarySum = BigDecimal.ZERO;
        }
        if (paymentSum == null) {
            paymentSum = BigDecimal.ZERO;
        }
    }
}
