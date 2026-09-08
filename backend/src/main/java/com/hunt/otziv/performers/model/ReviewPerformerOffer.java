package com.hunt.otziv.performers.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "review_performer_offers")
public class ReviewPerformerOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "offer_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false)
    private ReviewPerformerAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performer_id", nullable = false)
    private PerformerProfile performer;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private PerformerOfferStatus status = PerformerOfferStatus.OFFERED;

    @Column(name = "offered_at", nullable = false)
    private LocalDateTime offeredAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(name = "telegram_message_id")
    private Integer telegramMessageId;

    @Builder.Default
    @Column(name = "delivery_state", nullable = false, length = 32)
    private String deliveryState = "LEGACY_UNKNOWN";

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Builder.Default
    @Column(name = "response_ttl_minutes", nullable = false)
    private int responseTtlMinutes = 10;

    @Column(name = "decline_reason", length = 1000)
    private String declineReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (offeredAt == null) {
            offeredAt = createdAt;
        }
    }
}
