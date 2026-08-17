package com.hunt.otziv.payments.repository;

import java.time.LocalDateTime;

/** Confirmed legacy payment source that predates immutable actual-recipient attribution. */
public interface ManualPaymentLegacyMonthlySourceProjection {

    Long getSourceId();

    Long getAmountKopecks();

    LocalDateTime getEffectiveAt();
}
