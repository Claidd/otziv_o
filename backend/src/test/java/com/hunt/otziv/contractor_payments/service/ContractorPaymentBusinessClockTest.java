package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ContractorPaymentBusinessClockTest {

    @Test
    void monthBoundaryUsesIrkutskCalendarInsteadOfJvmOrUtcCalendar() {
        Clock utcBoundary = Clock.fixed(Instant.parse("2026-08-31T18:30:00Z"), ZoneId.of("UTC"));

        ContractorPaymentBusinessClock clock = new ContractorPaymentBusinessClock(
                utcBoundary,
                ZoneId.of("Asia/Irkutsk")
        );

        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(clock.now().getMonthValue()).isEqualTo(9);
    }
}
