package com.hunt.otziv.l_lead.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
@Table(name = "device_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class DeviceToken {

    @Id
    @EqualsAndHashCode.Include
    @ToString.Include
    private String token; // UUID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telephone_id", nullable = false, unique = true)
    private Telephone telephone;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
