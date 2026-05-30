package com.hunt.otziv.client_messages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchiveCompanyMessageCandidateRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void queryUsesCompanyStatusChangedAtAsCandidateSource() {
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<ArchiveCompanyMessageCandidate>>any()
        )).thenReturn(List.of());

        repository().findCandidates(
                LocalDateTime.of(2026, 2, 24, 10, 0),
                100,
                "На стопе",
                List.of("Оплачено", "Архив", "Бан"),
                List.of("PENDING", "FAILED")
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).query(
                sqlCaptor.capture(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<ArchiveCompanyMessageCandidate>>any()
        );

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("FROM companies c"));
        assertTrue(sql.contains("c.company_status_changed_at <= :cutoff"));
        assertTrue(sql.contains("LEFT JOIN archive_orders ao"));
        assertFalse(sql.contains("FROM archive_orders ao"));
    }

    @Test
    void blockerQueryChecksLiveOrdersAndOpenRequestsAtSendTime() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(true);

        boolean blocked = repository().hasArchiveReorderBlocker(
                42L,
                List.of("Оплачено", "Архив", "Бан"),
                List.of("PENDING", "FAILED")
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).queryForObject(
                sqlCaptor.capture(),
                any(MapSqlParameterSource.class),
                eq(Boolean.class)
        );

        String sql = sqlCaptor.getValue();
        assertTrue(blocked);
        assertTrue(sql.contains("FROM orders live_order"));
        assertTrue(sql.contains("FROM next_order_requests request"));
        assertTrue(sql.contains("live_order.order_company = :companyId"));
    }

    private ArchiveCompanyMessageCandidateRepository repository() {
        return new ArchiveCompanyMessageCandidateRepository(jdbc);
    }
}
