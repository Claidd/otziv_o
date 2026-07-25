package com.hunt.otziv.client_chat_control.model;

import com.hunt.otziv.u_users.model.User;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "client_chat_participant_identities",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_client_chat_identity",
                columnNames = {"platform", "chat_id", "identity_key"}
        )
)
public class ClientChatParticipantIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ClientChatPlatform platform;

    @Column(name = "chat_id", nullable = false, length = 160)
    private String chatId;

    @Column(name = "identity_key", nullable = false, length = 220)
    private String identityKey;

    @Column(name = "external_id", length = 160)
    private String externalId;

    @Column(name = "normalized_name", length = 255)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 24)
    private ClientChatSenderRole senderRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_user_id")
    private User linkedUser;

    @Column(name = "verified_by_user_id")
    private Long verifiedByUserId;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(nullable = false)
    private boolean active = true;

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
