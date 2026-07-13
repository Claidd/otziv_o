package com.hunt.otziv.manager_daily_summary.model;

import com.hunt.otziv.u_users.model.User;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "manager_summary_delivery_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_manager_summary_delivery", columnNames = {"summary_date", "recipient_user_id", "channel"})
)
public class ManagerSummaryDeliveryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "delivery_id") private Long id;
    @Column(name = "summary_date", nullable = false) private LocalDate summaryDate;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_user_id", nullable = false) private User recipient;
    @Column(name = "recipient_chat_id", nullable = false) private Long recipientChatId;
    @Column(name = "channel", nullable = false, length = 24) private String channel;
    @Column(name = "status", nullable = false, length = 24) private String status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "sent_at") private LocalDateTime sentAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @PrePersist void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (channel == null) channel = "TELEGRAM";
        if (status == null) status = "PENDING";
    }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
