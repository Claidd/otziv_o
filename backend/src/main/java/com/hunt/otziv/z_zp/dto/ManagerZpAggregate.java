package com.hunt.otziv.z_zp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManagerZpAggregate {

    private BigDecimal totalSum;
    private long orderCount;
    private long reviewAmount;
}
