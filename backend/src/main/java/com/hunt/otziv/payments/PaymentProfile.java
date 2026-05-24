package com.hunt.otziv.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(
        name = "payment_profiles",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_profiles_code", columnNames = "code"),
                @UniqueConstraint(name = "uk_payment_profiles_terminal_key", columnNames = "terminal_key")
        },
        indexes = {
                @Index(name = "idx_payment_profiles_enabled", columnList = "enabled"),
                @Index(name = "idx_payment_profiles_default", columnList = "is_default")
        }
)
public class PaymentProfile {

    public static final String PROVIDER_TBANK = "T_BANK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 32)
    private String provider = PROVIDER_TBANK;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "terminal_key", nullable = false, length = 64)
    private String terminalKey;

    @Column(name = "password_env_key", length = 160)
    private String passwordEnvKey;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    private boolean defaultProfile = false;

    @Column(name = "test_mode", nullable = false)
    private boolean testMode = true;

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
