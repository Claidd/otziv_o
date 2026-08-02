package com.hunt.otziv.b_bots.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "bot_browser_sessions")
public class BotBrowserSession {

    @Id
    @Column(name = "session_id", nullable = false, length = 36, updatable = false)
    private String sessionId;

    @Column(name = "bot_id", nullable = false, updatable = false)
    private Long botId;

    @Column(name = "external_key_snapshot", nullable = false, length = 96, updatable = false)
    private String externalKeySnapshot;

    @Column(name = "opener_username", nullable = false, length = 255, updatable = false)
    private String openerUsername;

    @Column(name = "opener_subject", nullable = false, length = 512, updatable = false)
    private String openerSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BotBrowserSessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "heartbeat_expires_at", nullable = false)
    private LocalDateTime heartbeatExpiresAt;

    @Column(name = "absolute_expires_at", nullable = false, updatable = false)
    private LocalDateTime absoluteExpiresAt;

    @Column(name = "close_requested_at")
    private LocalDateTime closeRequestedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "next_stop_retry_at")
    private LocalDateTime nextStopRetryAt;

    @Column(name = "stop_attempts", nullable = false)
    private int stopAttempts;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
