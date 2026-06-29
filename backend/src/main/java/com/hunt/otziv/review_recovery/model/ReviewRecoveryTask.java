package com.hunt.otziv.review_recovery.model;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "review_recovery_tasks")
public class ReviewRecoveryTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_recovery_task_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_recovery_task_batch", nullable = false)
    private ReviewRecoveryBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_task_order")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_task_review")
    private Review sourceReview;

    @Column(name = "review_recovery_task_archive_order_id")
    private Long archiveOrderId;

    @Column(name = "review_recovery_task_archive_review_id")
    private Long archiveReviewId;

    @Column(name = "review_recovery_task_archive_company_id")
    private Long archiveCompanyId;

    @Column(name = "review_recovery_task_archive_order_details_id")
    private UUID archiveOrderDetailsId;

    @Column(name = "review_recovery_task_archive_order_status")
    private String archiveOrderStatus;

    @Column(name = "review_recovery_task_archive_company_title")
    private String archiveCompanyTitle;

    @Column(name = "review_recovery_task_archive_company_note", columnDefinition = "TEXT")
    private String archiveCompanyNote;

    @Column(name = "review_recovery_task_archive_order_note", columnDefinition = "TEXT")
    private String archiveOrderNote;

    @Column(name = "review_recovery_task_archive_filial_city")
    private String archiveFilialCity;

    @Column(name = "review_recovery_task_archive_filial_city_id")
    private Long archiveFilialCityId;

    @Column(name = "review_recovery_task_archive_filial_title")
    private String archiveFilialTitle;

    @Column(name = "review_recovery_task_archive_filial_url")
    private String archiveFilialUrl;

    @Column(name = "review_recovery_task_archive_category")
    private String archiveCategory;

    @Column(name = "review_recovery_task_archive_subcategory")
    private String archiveSubCategory;

    @Column(name = "review_recovery_task_archive_product_id")
    private Long archiveProductId;

    @Column(name = "review_recovery_task_archive_product_title")
    private String archiveProductTitle;

    @Column(name = "review_recovery_task_archive_review_created")
    private LocalDate archiveReviewCreated;

    @Column(name = "review_recovery_task_archive_review_changed")
    private LocalDate archiveReviewChanged;

    @Column(name = "review_recovery_task_archive_review_published_date")
    private LocalDate archiveReviewPublishedDate;

    @Builder.Default
    @Column(name = "review_recovery_task_archive_review_publish", nullable = false)
    private boolean archiveReviewPublish = false;

    @Builder.Default
    @Column(name = "review_recovery_task_archive_review_vigul", nullable = false)
    private boolean archiveReviewVigul = false;

    @Column(name = "review_recovery_task_archive_review_price")
    private BigDecimal archiveReviewPrice;

    @Column(name = "review_recovery_task_archive_review_url")
    private String archiveReviewUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_task_worker")
    private Worker worker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_task_manager")
    private Manager manager;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_task_bot")
    private Bot bot;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_recovery_task_status", nullable = false, length = 32)
    private ReviewRecoveryTaskStatus status;

    @Column(name = "review_recovery_task_original_text", columnDefinition = "LONGTEXT")
    private String originalText;

    @Column(name = "review_recovery_task_recovery_text", nullable = false, columnDefinition = "LONGTEXT")
    private String recoveryText;

    @Column(name = "review_recovery_task_original_answer", columnDefinition = "LONGTEXT")
    private String originalAnswer;

    @Column(name = "review_recovery_task_recovery_answer", columnDefinition = "LONGTEXT")
    private String recoveryAnswer;

    @Column(name = "review_recovery_task_bot_login_snapshot")
    private String botLoginSnapshot;

    @Column(name = "review_recovery_task_bot_password_snapshot")
    private String botPasswordSnapshot;

    @Column(name = "review_recovery_task_bot_fio_snapshot")
    private String botFioSnapshot;

    @Column(name = "review_recovery_task_scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "review_recovery_task_completed_date")
    private LocalDate completedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_task_created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_task_completed_by")
    private User completedBy;

    @Column(name = "review_recovery_task_created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "review_recovery_task_updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
