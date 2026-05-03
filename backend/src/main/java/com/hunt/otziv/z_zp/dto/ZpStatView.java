package com.hunt.otziv.z_zp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface ZpStatView {
    LocalDate getCreated();
    BigDecimal getSum();
    int getAmount();
}
