package com.hunt.otziv.l_lead.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "lead_sync_queue")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadSyncQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long leadId;

    @Column(length = 20)
    private String telephoneLead;

    @Column(length = 50)
    private String lidStatus;

    private LocalDateTime lastSeen;

    /** Полный JSON-пэйлоад, который будем ретраить как есть */
    @Lob
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /** Количество неудачных попыток */
    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastAttemptAt;

    @Column(length = 500)
    private String lastError;

    public LeadSyncQueue(Long leadId, String telephoneLead, LocalDateTime lastSeen, String lidStatus) {
        this.leadId = leadId;
        this.telephoneLead = telephoneLead;
        this.lastSeen = lastSeen;
        this.lidStatus = lidStatus;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
        this.lastAttemptAt = this.createdAt;
    }

    public LeadSyncQueue(String telephoneLead, LocalDateTime lastSeen, String lidStatus) {}

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.lastAttemptAt == null) {
            this.lastAttemptAt = this.createdAt;
        }
    }
}
