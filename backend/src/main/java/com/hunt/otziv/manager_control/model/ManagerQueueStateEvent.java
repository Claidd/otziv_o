package com.hunt.otziv.manager_control.model;

import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "manager_queue_state_events")
public class ManagerQueueStateEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "state_event_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manager_id", nullable = false)
    private Manager manager;
    @Column(name = "state_code", nullable = false, length = 24)
    private String stateCode;
    @Column(name = "open_action_count", nullable = false) private long openActionCount;
    @Column(name = "within_target_count", nullable = false) private long withinTargetCount;
    @Column(name = "target_missed_count", nullable = false) private long targetMissedCount;
    @Column(name = "overdue_count", nullable = false) private long overdueCount;
    @Column(name = "observed_at", nullable = false) private LocalDateTime observedAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (observedAt == null) observedAt = LocalDateTime.now();
        createdAt = LocalDateTime.now();
    }
}
