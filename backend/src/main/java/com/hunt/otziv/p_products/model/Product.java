package com.hunt.otziv.p_products.model;

import com.hunt.otziv.c_categories.model.ProductCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    @Column(name = "product_title")
    private String title;

    @Column(name = "product_price")
    private BigDecimal price;

    @Column(name = "product_photo")
    private Boolean photo;

    @Builder.Default
    @Column(name = "product_requires_performer", nullable = false)
    private boolean requiresPerformer = false;

    @Column(name = "product_target_platform")
    private String targetPlatform;

    @Builder.Default
    @Column(name = "product_performer_reward_percent", nullable = false)
    private BigDecimal performerRewardPercent = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "product_specialist_reward_percent", nullable = false)
    private BigDecimal specialistRewardPercent = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "product_manager_reward_percent", nullable = false)
    private BigDecimal managerRewardPercent = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_category")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ProductCategory productCategory;
}
