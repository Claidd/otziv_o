package com.hunt.otziv.review_recovery.model;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
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

import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "review_recovery_batches")
public class ReviewRecoveryBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_recovery_batch_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_recovery_batch_order", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_batch_manager")
    private Manager manager;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_recovery_batch_status", nullable = false, length = 32)
    private ReviewRecoveryBatchStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_batch_created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_recovery_batch_client_notified_by")
    private User clientNotifiedBy;

    @Column(name = "review_recovery_batch_completed_at")
    private Instant completedAt;

    @Column(name = "review_recovery_batch_client_notified_at")
    private Instant clientNotifiedAt;

    @Column(name = "review_recovery_batch_archived_at")
    private Instant archivedAt;

    @Column(name = "review_recovery_batch_created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "review_recovery_batch_updated_at", nullable = false)
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
