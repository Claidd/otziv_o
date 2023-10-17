package com.hunt.otziv.z_zp.dto;

import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZpDTO {
    private Long id;

    private String fio;

    private BigDecimal sum;

    private Long userId;

    private Long professionId;

    private Long orderId;


    private LocalDate created;

    private boolean active;
}
