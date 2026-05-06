package com.hunt.otziv.l_lead.model;

import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "promo_text_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_promo_assignment_manager_slot",
                columnNames = {"manager_id", "section_code", "button_key"}
        )
)
public class PromoTextAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id", nullable = false)
    private Manager manager;

    @Column(name = "section_code", nullable = false, length = 40)
    private String sectionCode;

    @Column(name = "button_key", nullable = false, length = 40)
    private String buttonKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promo_text_id", nullable = false)
    private PromoText promoText;
}
