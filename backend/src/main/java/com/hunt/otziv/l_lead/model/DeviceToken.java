package com.hunt.otziv.l_lead.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
@Table(name = "device_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceToken {

    @Id
    private String token; // UUID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telephone_id", nullable = false, unique = true)
    private Telephone telephone;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "active", nullable = false)
    private boolean active = true;
}

