package com.hunt.otziv.client_chat_control.model;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.Column;
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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "client_chat_unanswered_items")
public class ClientChatUnansweredItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 24)
    private ClientChatPlatform platform;

    @Column(name = "chat_id", nullable = false, length = 160)
    private String chatId;

    @Column(name = "chat_title", length = 255)
    private String chatTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Manager manager;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_client_message_id")
    private ClientChatMessage lastClientMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ClientChatUnansweredStatus status = ClientChatUnansweredStatus.OPEN;

    @Column(name = "sender_external_id", length = 160)
    private String senderExternalId;

    @Column(name = "sender_name", length = 255)
    private String senderName;

    @Column(name = "last_message_text", columnDefinition = "TEXT")
    private String lastMessageText;

    @Column(name = "first_opened_at", nullable = false)
    private LocalDateTime firstOpenedAt;

    @Column(name = "last_client_message_at", nullable = false)
    private LocalDateTime lastClientMessageAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "close_reason", length = 255)
    private String closeReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (firstOpenedAt == null) {
            firstOpenedAt = now;
        }
        if (lastClientMessageAt == null) {
            lastClientMessageAt = now;
        }
        if (status == null) {
            status = ClientChatUnansweredStatus.OPEN;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
