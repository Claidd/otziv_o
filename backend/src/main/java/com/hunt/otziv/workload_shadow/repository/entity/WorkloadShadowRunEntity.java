package com.hunt.otziv.workload_shadow.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;

@Entity
@Table(name = "workload_shadow_runs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicInsert
@Getter
public class WorkloadShadowRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "workload_shadow_run_id")
    private Long id;

    @Column(name = "trigger_type", nullable = false, length = 32)
    private String triggerType;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "started_at", nullable = false)
    private java.time.LocalDateTime startedAt;

    @Column(name = "instance_id", length = 120)
    private String instanceId;

    public static WorkloadShadowRunEntity running(
            String triggerType,
            java.time.LocalDateTime startedAt,
            String instanceId
    ) {
        WorkloadShadowRunEntity entity = new WorkloadShadowRunEntity();
        entity.triggerType = triggerType;
        entity.status = "RUNNING";
        entity.startedAt = startedAt;
        entity.instanceId = instanceId;
        return entity;
    }
}
