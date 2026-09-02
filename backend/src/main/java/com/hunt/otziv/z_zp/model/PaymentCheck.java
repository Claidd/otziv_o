package com.hunt.otziv.z_zp.model;

import com.hunt.otziv.z_zp.dto.PaymentCheckStatView;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "payment_check")
public class PaymentCheck implements PaymentCheckStatView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "check_id")
    private Long id;
    @Column(name = "check_title")
    private String title;
    @Column(name = "check_sum")
    private BigDecimal sum;
    /** Exact order/item count added to Company.counterPay by this payment. */
    @Column(name = "check_paid_amount")
    private Integer paidAmount;
    /** Payment source that created this cycle; null for non-link/legacy payments. */
    @Column(name = "check_payment_link")
    private Long paymentLinkId;
    @Column(name = "check_company")
    private Long companyId;
    @Column(name = "check_order")
    private Long orderId;
    @Column(name = "check_payment_status_guard")
    private Long paymentStatusGuard;
    @Column(name = "check_manager")
    private Long managerId;
    @Column(name = "check_worker")
    private Long workerId;
    @CreationTimestamp
    @Column(name = "check_date")
    private LocalDate created;
    @Column(name = "check_active")
    private boolean active;
}
