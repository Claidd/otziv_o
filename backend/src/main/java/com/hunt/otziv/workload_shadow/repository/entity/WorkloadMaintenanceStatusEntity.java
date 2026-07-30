package com.hunt.otziv.workload_shadow.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "workload_maintenance_status")
public class WorkloadMaintenanceStatusEntity {

    @Id
    @Column(name = "maintenance_task", nullable = false, length = 24)
    private String task;

    @Column(name = "last_started_at")
    private LocalDateTime lastStartedAt;

    @Column(name = "last_succeeded_at")
    private LocalDateTime lastSucceededAt;

    @Column(name = "last_failed_at")
    private LocalDateTime lastFailedAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_error_code", length = 120)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
