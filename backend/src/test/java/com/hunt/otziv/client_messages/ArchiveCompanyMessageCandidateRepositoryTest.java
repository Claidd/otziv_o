package com.hunt.otziv.client_messages;

import com.hunt.otziv.client_messages.dto.ArchiveCompanyMessageCandidate;
import com.hunt.otziv.client_messages.repository.ArchiveCompanyMessageCandidateRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(sql.contains("WITH eligible_companies AS"));
        assertTrue(sql.contains("FROM archive_orders archived"));
        assertTrue(sql.contains("ORDER BY archived.archived_at DESC, archived.order_id DESC"));
        assertTrue(sql.contains("FROM scheduled_client_message_state state"));
        assertTrue(sql.contains("state.target_key = CONCAT"));
        assertFalse(sql.contains("GROUP_CONCAT"));
        assertFalse(sql.contains("FROM archive_orders ao"));
    }

    @Test
    void pageQueryContinuesAfterStatusTimestampAndCompanyId() {
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<ArchiveCompanyMessageCandidate>>any()
        )).thenReturn(List.of());
        LocalDateTime cursorTime = LocalDateTime.of(2024, 8, 10, 0, 0);

        repository().findUnsynchronizedCandidatesAfter(
                LocalDateTime.of(2026, 5, 12, 10, 0),
                200,
                "На стопе",
                List.of("Оплачено", "Архив", "Бан"),
                List.of("PENDING", "FAILED"),
                cursorTime,
                900L
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        org.mockito.Mockito.verify(jdbc).query(
                sqlCaptor.capture(),
                paramsCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<ArchiveCompanyMessageCandidate>>any()
        );

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("c.company_status_changed_at > :afterStatusChangedAt"));
        assertTrue(sql.contains("c.company_id > :afterCompanyId"));
        assertEquals(java.sql.Timestamp.valueOf(cursorTime), paramsCaptor.getValue().getValue("afterStatusChangedAt"));
        assertEquals(900L, paramsCaptor.getValue().getValue("afterCompanyId"));
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
