package com.hunt.otziv.workload_shadow.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "workload_shadow_transfer_cases")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkloadShadowTransferCaseEntity {

    @Id
    @Column(name = "workload_shadow_transfer_case_id")
    private Long id;
}
