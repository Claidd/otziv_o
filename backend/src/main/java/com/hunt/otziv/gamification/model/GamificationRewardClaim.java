package com.hunt.otziv.gamification.model;

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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "gamification_reward_claims")
public class GamificationRewardClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "claim_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reward_id", nullable = false)
    private GamificationReward reward;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "status", nullable = false, length = 32) private String status;
    @Column(name = "token_cost", nullable = false) private int tokenCost;
    @Column(name = "comment", length = 1000) private String comment;
    @Column(name = "admin_comment", length = 1000) private String adminComment;
    @Column(name = "requested_at", nullable = false) private LocalDateTime requestedAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "fulfilled_at") private LocalDateTime fulfilledAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        requestedAt = now;
        updatedAt = now;
        if (status == null) status = "REQUESTED";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
