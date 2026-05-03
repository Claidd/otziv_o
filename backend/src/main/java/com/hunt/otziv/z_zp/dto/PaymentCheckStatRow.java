package com.hunt.otziv.z_zp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentCheckStatRow(LocalDate created, BigDecimal sum) implements PaymentCheckStatView {
    @Override
    public LocalDate getCreated() {
        return created;
    }

    @Override
    public BigDecimal getSum() {
        return sum;
    }
}
