package com.hunt.otziv.payments.model;

import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "manual_payment_tasks",
        indexes = {
                @Index(name = "idx_manual_payment_tasks_manager", columnList = "manager_id"),
                @Index(name = "idx_manual_payment_tasks_profile_status", columnList = "payment_profile_id,status"),
                @Index(name = "idx_manual_payment_tasks_status", columnList = "status")
        }
)
public class ManualPaymentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id", nullable = false)
    private Manager manager;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_profile_id", nullable = false)
    private PaymentProfile paymentProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ManualPaymentTaskStatus status = ManualPaymentTaskStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_payment_type", nullable = false, length = 32)
    private ManualPaymentType manualPaymentType = ManualPaymentType.MOBILE_BANK;

    @Column(name = "manual_phone", length = 32)
    private String manualPhone;

    @Column(name = "manual_recipient_name", length = 160)
    private String manualRecipientName = ManualPaymentType.DEFAULT_MANUAL_RECIPIENT_NAME;

    @Column(name = "manual_payment_url", length = 512)
    private String manualPaymentUrl = ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL;

    @Column(name = "manual_payment_button_label", length = 80)
    private String manualPaymentButtonLabel = ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL;

    @Column(name = "target_amount_kopecks", nullable = false)
    private long targetAmountKopecks;

    @Column(length = 255)
    private String comment;

    @Column(name = "created_by", length = 160)
    private String createdBy;

    @Column(name = "updated_by", length = 160)
    private String updatedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
