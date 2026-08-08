package com.hunt.otziv.contractor_payments.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One calendar for every contractor-payment occurrence date. Keeping the
 * clock here prevents a JVM/default-zone difference from moving an accrual or
 * correction into another accounting month.
 */
@Component
public class ContractorPaymentBusinessClock {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Irkutsk");

    private final Clock clock;
    private final ZoneId businessZone;

    @Autowired
    public ContractorPaymentBusinessClock(
            @Value("${otziv.contractor-payments.business-zone:Asia/Irkutsk}") String zoneId
    ) {
        this(Clock.systemUTC(), parseZone(zoneId));
    }

    ContractorPaymentBusinessClock(Clock clock, ZoneId businessZone) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.businessZone = businessZone == null ? DEFAULT_ZONE : businessZone;
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), businessZone);
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), businessZone);
    }

    private static ZoneId parseZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (DateTimeException ignored) {
            return DEFAULT_ZONE;
        }
    }
}
