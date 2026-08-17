package com.hunt.otziv.payments.repository;

import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerEventType;

public interface ManualPaymentTaskLedgerDeltaProjection {
    long getReservedDeltaKopecks();

    long getConfirmedDeltaKopecks();

    long getRedirectedAmountKopecks();

    ManualPaymentTaskLedgerEventType getEventType();

    boolean isVerified();
}
