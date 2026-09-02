package com.hunt.otziv.z_zp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A daily salary row read from the canonical analytics salary source. */
public record CanonicalZpStatRow(
        LocalDate created,
        BigDecimal sum,
        int amount,
        long entryCount
) implements ZpStatView {
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

    @Override
    public long getEntryCount() {
        return entryCount;
    }
}
