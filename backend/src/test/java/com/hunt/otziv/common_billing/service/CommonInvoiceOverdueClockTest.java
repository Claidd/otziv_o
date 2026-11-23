package com.hunt.otziv.common_billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CommonInvoiceOverdueClockTest {

    private static final LocalDateTime SENT = LocalDateTime.of(2026, 10, 20, 12, 0);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CommonInvoiceOverdueClock clock = new CommonInvoiceOverdueClock(jdbc);

    @Test
    void anOrdinaryInvoiceCountsFromItsSending() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setSentAt(SENT);

        assertThat(clock.start(invoice)).contains(SENT);
        verifyNoInteractions(jdbc);
    }

    @Test
    void aReopenedInvoiceCountsFromItsReopeningNotFromTheOldSending() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setSentAt(SENT);
        LocalDateTime reopened = LocalDateTime.of(2026, 11, 22, 15, 30);
        invoice.setOverdueTermRestartedAt(reopened);

        assertThat(clock.start(invoice)).contains(reopened);
    }

    @Test
    void aLaterSendingAfterAReopeningStartsItsOwnTerm() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setOverdueTermRestartedAt(SENT.minusDays(5));
        invoice.setSentAt(SENT);

        assertThat(clock.start(invoice)).contains(SENT);
    }

    @Test
    void anUnsentInvoiceHasNoTermEvenIfItWasReopened() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setOverdueTermRestartedAt(SENT);

        assertThat(clock.start(invoice)).isEmpty();
    }
}
