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
@Table(name = "company_contacts")
public class CompanyContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_contact_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 30)
    private CompanyContactType type;

    @Column(name = "contact_value", nullable = false, length = 1000)
    private String value;

    @Column(name = "contact_normalized", length = 1000)
    private String normalizedValue;

    @Column(name = "primary_contact", nullable = false)
    private boolean primaryContact;

    @Column(name = "contact_source", nullable = false, length = 30)
    private String source;

    @Column(name = "source_lead_id")
    private Long sourceLeadId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
