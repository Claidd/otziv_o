package com.hunt.otziv.external_review_checks.model;

import com.hunt.otziv.r_review.model.Review;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "review_external_checks")
public class ReviewExternalCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_external_check_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "filial_id")
    private Long filialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 32)
    private ExternalReviewCheckPlatform platform;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private ExternalReviewCheckSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ExternalReviewCheckStatus status;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "check_after")
    private LocalDateTime checkAfter;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "filial_url", length = 1000)
    private String filialUrl;

    @Column(name = "screenshot_url", length = 1024)
    private String screenshotUrl;

    @Column(name = "screenshot_key", length = 1024)
    private String screenshotKey;

    @Column(name = "matched_text_excerpt", length = 1000)
    private String matchedTextExcerpt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "worker_trace_id", length = 128)
    private String workerTraceId;

    /**
     * Nullable for rows created before the R3 dual-write rollout. New rows get
     * a SHA-256 value; automatic checks use a deterministic review-scoped key.
     */
    @Column(name = "deduplication_key_hash", length = 32, columnDefinition = "BINARY(32)")
    private byte[] deduplicationKeyHash;

    @Column(name = "processing_token", length = 36, columnDefinition = "CHAR(36)")
    @ToString.Exclude
    private String processingToken;

    @Column(name = "processing_owner", length = 128)
    private String processingOwner;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "processing_lease_until")
    private LocalDateTime processingLeaseUntil;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = ExternalReviewCheckStatus.PENDING;
        }
        if (source == null) {
            source = ExternalReviewCheckSource.AUTO_SCREENSHOT;
        }
        if (platform == null) {
            platform = ExternalReviewCheckPlatform.UNKNOWN;
        }
        if (checkAfter == null) {
            checkAfter = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
