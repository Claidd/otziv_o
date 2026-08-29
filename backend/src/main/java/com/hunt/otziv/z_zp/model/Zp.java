package com.hunt.otziv.z_zp.model;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.z_zp.dto.ZpStatView;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "zp")
public class Zp implements ZpStatView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "zp_id")
    private Long id;
    @Column(name = "zp_fio")
    private String fio;
    @Column(name = "zp_sum")
    private BigDecimal sum;
    @Column(name = "zp_user")
    private Long userId;
    @Column(name = "zp_profession")
    private Long professionId;
    @Column(name = "zp_order")
    private Long orderId;
    @Column(name = "zp_payment_status_guard")
    private Long paymentStatusGuardId;
    @Column(name = "zp_amount")
    private int amount;
    @Column(name = "zp_date")
    private LocalDate created;
    @Column(name = "zp_updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
    @Column(name = "zp_active")
    private boolean active;
    @Column(name = "zp_source")
    private String source;
    @Enumerated(EnumType.STRING)
    @Column(name = "zp_contractor_role")
    private ContractorRole contractorRole;
    @Column(name = "zp_attribution_final", nullable = false)
    private boolean attributionFinal;
    @Column(name = "zp_reward_basis", precision = 19, scale = 2)
    private BigDecimal rewardBasis;
    @Column(name = "zp_attribution_snapshot", columnDefinition = "TEXT")
    private String attributionSnapshot;

    @PrePersist
    protected void assignOccurrenceDate() {
        if (created == null) {
            created = LocalDate.now();
        }
    }
}
