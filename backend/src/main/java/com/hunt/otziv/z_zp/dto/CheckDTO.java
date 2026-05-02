package com.hunt.otziv.z_zp.dto;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
public class CheckDTO {

    private Long id;

    private String title;

    private BigDecimal sum;

    private Long companyId;

    private Long orderId;

    private Long managerId;

    private Long workerId;

    private LocalDate created;

    private boolean active;
}
