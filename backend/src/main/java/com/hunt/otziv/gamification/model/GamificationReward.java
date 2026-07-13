package com.hunt.otziv.gamification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "gamification_rewards")
public class GamificationReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reward_id")
    private Long id;

    @Column(name = "reward_code", nullable = false, unique = true, length = 80)
    private String code;
    @Column(name = "title", nullable = false, length = 160) private String title;
    @Column(name = "description", length = 1000) private String description;
    @Column(name = "reward_type", nullable = false, length = 32) private String rewardType;
    @Column(name = "icon", length = 80) private String icon;
    @Column(name = "image_url", length = 600) private String imageUrl;
    @Column(name = "token_cost", nullable = false) private int tokenCost;
    @Column(name = "required_level", nullable = false) private int requiredLevel;
    @Column(name = "stock_quantity") private Integer stockQuantity;
    @Column(name = "active", nullable = false) private boolean active;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (rewardType == null) rewardType = "VIRTUAL";
        if (requiredLevel < 1) requiredLevel = 1;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
