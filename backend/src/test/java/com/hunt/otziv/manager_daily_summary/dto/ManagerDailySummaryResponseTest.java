package com.hunt.otziv.manager_daily_summary.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ManagerDailySummaryResponseTest {

    @Test
    void auditViewRemovesOnlyStillOpenUnansweredMessagesAndKeepsOtherCounters() {
        var day = row(5, 2);

        var audit = day.withoutUnanswered(1);

        assertThat(audit.unansweredCount()).isEqualTo(1);
        assertThat(audit.taskOpen()).isEqualTo(4);
        assertThat(audit.overdueCount()).isEqualTo(day.overdueCount());
        assertThat(audit.problemCount()).isEqualTo(day.problemCount());
        assertThat(day.withoutUnanswered(0)).isSameAs(day);
        assertThat(day.withoutUnanswered(9).unansweredCount()).isZero();
        assertThat(day.withoutUnanswered(9).taskOpen()).isEqualTo(3);
    }

    private ManagerDailySummaryResponse row(long open, long unanswered) {
        return new ManagerDailySummaryResponse(LocalDate.of(2026, 9, 14), 3L, 30L, "Анжелика Б.", 60, "C",
                10, 5, open, 0, 5, 0, 0, 0, BigDecimal.valueOf(50), 1, 1, unanswered, 1,
                0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, "COMPLETED", 0,
                "CALCULATED", LocalDateTime.of(2026, 9, 14, 22, 5));
    }
}
