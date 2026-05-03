package com.hunt.otziv.z_zp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ZpStatRow(LocalDate created, BigDecimal sum, int amount) implements ZpStatView {
    @Override
    public LocalDate getCreated() {
        return created;
    }

    @Override
    public BigDecimal getSum() {
        return sum;
    }

    @Override
    public int getAmount() {
        return amount;
    }
}
