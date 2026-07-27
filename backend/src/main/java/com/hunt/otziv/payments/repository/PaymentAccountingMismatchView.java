package com.hunt.otziv.payments.repository;

import java.math.BigDecimal;

public interface PaymentAccountingMismatchView {
    Long getOrderId();

    BigDecimal getConfirmedKopecks();

    BigDecimal getCheckKopecks();
}
