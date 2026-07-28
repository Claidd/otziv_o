package com.hunt.otziv.workload_shadow.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "workload_shadow_worker_daily")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkloadShadowWorkerDailyEntity {

    @Id
    @Column(name = "workload_shadow_worker_daily_id")
    private Long id;
}
