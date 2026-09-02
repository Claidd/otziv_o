package com.hunt.otziv.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class PaymentFinancialAnalyticsRepairServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AnalyticsAggregateRebuildService rebuildService;
    @Mock private AnalyticsAggregateVerificationService verificationService;
    @InjectMocks private PaymentFinancialAnalyticsRepairService service;

    @Test
    void affectedMonthsIncludesAuditedWorkerReattribution() {
        when(jdbcTemplate.query(
                anyString(),
                ArgumentMatchers.<RowMapper<LocalDate>>any()
        )).thenReturn(List.of());

        service.affectedMonths();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                ArgumentMatchers.<RowMapper<LocalDate>>any()
        );
        assertThat(sql.getValue())
                .contains("system:flyway-v281")
                .contains("PAYMENT_CHECK_WORKER_REATTRIBUTED")
                .contains("payment.check_id = CAST(audit_event.entity_id AS UNSIGNED)");
    }
}
