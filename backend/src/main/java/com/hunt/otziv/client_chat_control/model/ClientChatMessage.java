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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "client_chat_messages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_client_chat_message_external",
                columnNames = {"platform", "chat_id", "external_message_id"}
        )
)
public class ClientChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 24)
    private ClientChatPlatform platform;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 24)
    private ClientChatDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 24)
    private ClientChatSenderRole senderRole;

    @Column(name = "chat_id", nullable = false, length = 160)
    private String chatId;

    @Column(name = "chat_title", length = 255)
    private String chatTitle;

    @Column(name = "external_message_id", length = 255)
    private String externalMessageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Manager manager;

    @Column(name = "sender_external_id", length = 160)
    private String senderExternalId;

    @Column(name = "sender_name", length = 255)
    private String senderName;

    @Column(name = "message_text", columnDefinition = "TEXT")
    private String messageText;

    @Column(name = "message_at", nullable = false)
    private LocalDateTime messageAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (messageAt == null) {
            messageAt = LocalDateTime.now();
        }
        createdAt = LocalDateTime.now();
    }
}
