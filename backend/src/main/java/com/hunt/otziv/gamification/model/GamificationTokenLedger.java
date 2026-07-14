package com.hunt.otziv.gamification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "gamification_token_ledger")
public class GamificationTokenLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_entry_id")
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "amount", nullable = false) private int amount;
    @Column(name = "reason_code", nullable = false, length = 80) private String reasonCode;
    @Column(name = "description", length = 500) private String description;
    @Column(name = "unique_entry_key", nullable = false, unique = true, length = 190) private String uniqueEntryKey;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
