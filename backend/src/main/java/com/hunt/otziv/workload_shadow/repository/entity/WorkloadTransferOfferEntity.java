package com.hunt.otziv.workload_shadow.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "workload_transfer_offers")
public class WorkloadTransferOfferEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "workload_transfer_offer_id")
    private Long id;

    @Column(name = "offer_token", nullable = false, length = 36)
    private String offerToken;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
