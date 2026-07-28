package com.hunt.otziv.workload_shadow.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "workload_shadow_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkloadShadowEventEntity {

    @Id
    @Column(name = "workload_shadow_event_id")
    private Long id;
}
