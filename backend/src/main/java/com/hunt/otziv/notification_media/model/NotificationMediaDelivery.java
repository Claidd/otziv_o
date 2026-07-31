package com.hunt.otziv.notification_media.model;

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
@Table(name = "notification_media_deliveries")
public class NotificationMediaDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "delivery_id")
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "asset_id")
    private Long assetId;

    @Column(name = "event_code", nullable = false, length = 80)
    private String eventCode;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "photo_sent", nullable = false)
    private boolean photoSent;

    @Column(name = "delivery_note", length = 255)
    private String deliveryNote;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @PrePersist
    void onCreate() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }
}
