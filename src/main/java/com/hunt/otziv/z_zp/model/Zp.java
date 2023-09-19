package com.hunt.otziv.z_zp.model;

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
@Table(name = "zp")
public class Zp {

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
    @CreationTimestamp
    @Column(name = "zp_date")
    private LocalDate created;
    @Column(name = "zp_active")
    private boolean active;
}
