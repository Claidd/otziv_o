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

    /**
     * ID лида в локальной БД (или общий ID, если синхронизируется с VPS).
     */
    @Column(nullable = false)
    private Long leadId;

    /**
     * Телефон лида (для удобства логов и поиска, не обязателен).
     */
    @Column(length = 20)
    private String telephoneLead;

    /**
     * Последний статус лида (например, "Новый", "Оффлайн").
     */
    @Column(length = 50)
    private String lidStatus;

    /**
     * Время последнего "lastSeen" (Иркутское время).
     */
    private LocalDateTime lastSeen;

    /**
     * Количество неудачных попыток синхронизации.
     */
    @Column(nullable = false)
    private int retryCount;

    /**
     * Дата/время, когда эта запись была добавлена в очередь.
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Дата/время последней попытки синхронизации.
     */
    private LocalDateTime lastAttemptAt;

    /**
     * Причина последней ошибки (для отладки).
     */
    @Column(length = 500)
    private String lastError;
    

    public LeadSyncQueue(Long leadId, String telephoneLead, LocalDateTime lastSeen, String lidStatus) {
        this.leadId = leadId;
        this.telephoneLead = telephoneLead;
        this.lastSeen = lastSeen;
        this.lidStatus = lidStatus;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
    }

    public LeadSyncQueue(String telephoneLead, LocalDateTime lastSeen, String lidStatus) {
    }


    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

