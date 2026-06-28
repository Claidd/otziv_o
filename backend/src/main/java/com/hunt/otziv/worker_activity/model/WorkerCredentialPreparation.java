package com.hunt.otziv.worker_activity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "worker_credential_preparations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_worker_credential_preparation_worker_scope", columnNames = {"worker_user_id", "scope"})
        },
        indexes = {
                @Index(name = "idx_worker_credential_preparation_review", columnList = "review_id"),
                @Index(name = "idx_worker_credential_preparation_updated", columnList = "updated_at")
        }
)
public class WorkerCredentialPreparation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preparation_id")
    private Long id;

    @Column(name = "worker_user_id", nullable = false)
    private Long workerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 30)
    private WorkerCredentialPreparationScope scope;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "bot_id")
    private Long botId;

    @Column(name = "login_copied_at")
    private LocalDateTime loginCopiedAt;

    @Column(name = "password_copied_at")
    private LocalDateTime passwordCopiedAt;

    @Column(name = "source_page", length = 80)
    private String sourcePage;

    @Column(name = "source_entry", length = 80)
    private String sourceEntry;

    @Column(name = "source_section", length = 80)
    private String sourceSection;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
