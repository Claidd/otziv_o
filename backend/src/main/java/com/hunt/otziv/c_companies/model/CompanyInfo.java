package com.hunt.otziv.c_companies.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(exclude = "company")
@Table(name = "company_info")
public class CompanyInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_info_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, unique = true)
    @ToString.Exclude
    private Company company;

    @Column(name = "region", length = 255)
    private String region;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "industries", columnDefinition = "TEXT")
    private String industries;

    @Column(name = "company_type", columnDefinition = "TEXT")
    private String companyType;

    @Column(name = "info_source", nullable = false, length = 30)
    private String source;

    @Column(name = "source_lead_id")
    private Long sourceLeadId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
