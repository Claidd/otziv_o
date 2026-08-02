package com.hunt.otziv.common_billing.model;

import com.hunt.otziv.c_companies.model.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "common_billing_account_companies",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_common_billing_account_company",
                columnNames = {"account_id", "company_id"}
        )
)
public class CommonBillingAccountCompany {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_company_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CommonBillingAccount account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "reconcile_pending", nullable = false)
    private boolean reconcilePending;

    @Column(name = "reconcile_attempts", nullable = false)
    private int reconcileAttempts;

    @Column(name = "reconcile_next_attempt_at")
    private LocalDateTime reconcileNextAttemptAt;

    @Column(name = "reconcile_lease_token", length = 36)
    private String reconcileLeaseToken;

    @Column(name = "reconcile_lease_until")
    private LocalDateTime reconcileLeaseUntil;

    @Column(name = "reconcile_last_error", length = 512)
    private String reconcileLastError;

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
