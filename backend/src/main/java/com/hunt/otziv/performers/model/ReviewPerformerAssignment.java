package com.hunt.otziv.performers.model;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "review_performer_assignments")
public class ReviewPerformerAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_details_id")
    private OrderDetails orderDetails;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false, unique = true)
    private Review review;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performer_id")
    private PerformerProfile performer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id")
    private City city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "filial_id")
    private Filial filial;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "platform", nullable = false, length = 32)
    private PerformerPlatform platform = PerformerPlatform.OTHER;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private PerformerAssignmentStatus status = PerformerAssignmentStatus.CREATED;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "walked_at")
    private LocalDateTime walkedAt;

    @Column(name = "publish_available_at")
    private LocalDateTime publishAvailableAt;

    @Column(name = "published_claimed_at")
    private LocalDateTime publishedClaimedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "payout_amount", precision = 10, scale = 2)
    private BigDecimal payoutAmount;

    @Lob
    @Column(name = "client_approved_text_snapshot", columnDefinition = "TEXT")
    private String clientApprovedTextSnapshot;

    @Lob
    @Column(name = "performer_final_text", columnDefinition = "TEXT")
    private String performerFinalText;

    @Builder.Default
    @Column(name = "text_changed_by_performer", nullable = false)
    private boolean textChangedByPerformer = false;

    @Column(name = "publication_url", length = 1000)
    private String publicationUrl;

    @Column(name = "performer_publication_screenshot_url", length = 1000)
    private String performerPublicationScreenshotUrl;

    @Column(name = "manager_confirmation_screenshot_url", length = 1000)
    private String managerConfirmationScreenshotUrl;

    @Column(name = "instruction", length = 3000)
    private String instruction;

    @Column(name = "reject_reason", length = 1000)
    private String rejectReason;

    @Column(name = "manager_note", length = 2000)
    private String managerNote;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
