package com.hunt.otziv.common_billing.api;

import java.time.LocalDateTime;

/** Monthly usage of profile-directed common invoice payment routes. */
public interface CommonInvoiceProfileUsage {

    long used(Long profileId, LocalDateTime from, LocalDateTime to);

    long confirmed(Long profileId, LocalDateTime from, LocalDateTime to);

    long pendingAmount(Long profileId, LocalDateTime from, LocalDateTime to);

    long pendingCount(Long profileId, LocalDateTime from, LocalDateTime to);
}
