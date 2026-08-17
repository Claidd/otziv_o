package com.hunt.otziv.contractor_payments.model;

import com.hunt.otziv.security.credentials.EncryptedCredentialConverter;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "contractor_payment_profiles",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_contractor_payment_profiles_user_role",
                columnNames = {"user_id", "contractor_role"}
        )
)
public class ContractorPaymentProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "contractor_role", nullable = false, length = 24)
    private ContractorRole role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "live_enabled", nullable = false)
    private boolean liveEnabled;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "recipient_name", length = 512)
    private String recipientName;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "payment_phone", length = 512)
    private String paymentPhone;

    @Column(name = "bank_name", length = 120)
    private String bankName;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "payment_comment", length = 2048)
    private String paymentComment;

    @Column(name = "opening_balance_kopecks", nullable = false)
    private long openingBalanceKopecks;

    /**
     * Capacity promised to non-terminal typed manual-payment tasks but not yet
     * represented by contractor allocations. Mutations are serialized by this
     * profile row's PESSIMISTIC_WRITE mutex.
     */
    @Column(name = "manual_task_commitment_kopecks", nullable = false)
    private long manualTaskCommitmentKopecks;

    /** Exact, persisted operator acknowledgement covering task capacity overrun. */
    @Column(name = "manual_task_overrun_ack_kopecks", nullable = false)
    private long manualTaskOverrunAcknowledgedKopecks;

    @Column(name = "tracking_started_at", nullable = false)
    private LocalDateTime trackingStartedAt;

    @Column(name = "tracking_start_zp_id", nullable = false)
    private long trackingStartZpId;

    @Column(name = "ledger_sync_zp_id", nullable = false)
    private long ledgerSyncZpId;

    @Column(name = "ledger_sync_at")
    private LocalDateTime ledgerSyncAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (trackingStartedAt == null) {
            trackingStartedAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
