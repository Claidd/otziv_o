package com.hunt.otziv.archive.repository;

import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderArchiveRestoreRepositoryPaymentSourceTest {

    @Test
    void paidRestoreCopiesOnlyExactActiveCheckSourceWithProvenanceAndMarkerColumns() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        OrderArchiveRestoreRepository repository = new OrderArchiveRestoreRepository(jdbc);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", 42L)
                .addValue("targetStatusId", 7L);

        when(jdbc.queryForObject(contains("order_statuses target_status"), eq(params), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(contains("FROM archive_payment_check apc"), eq(params), eq(Long.class)))
                .thenReturn(List.of(77L));
        when(jdbc.queryForObject(contains("WHERE archived.id = :sourceId"),
                any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("return_recovery_outcome = 'MANUAL_RECONCILIATION'"),
                any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForObject(contains("FROM payment_links live"),
                any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForList(contains("INFORMATION_SCHEMA.COLUMNS"), anyMap(), eq(String.class)))
                .thenReturn(List.of(
                        "id", "token", "order_id", "status",
                        "return_recovery_processed_at",
                        "return_recovery_payment_check_id",
                        "return_recovery_outcome",
                        "return_recovery_resolved_at",
                        "return_recovery_resolved_by",
                        "return_recovery_resolution_reason"
                ));
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);

        assertEquals(1L, repository.restoreExactLinkedPaymentSource(params));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(MapSqlParameterSource.class));
        assertTrue(sql.getValue().contains("FROM archive_payment_links archived"));
        assertTrue(sql.getValue().contains("archived.id = :sourceId"));
        assertTrue(sql.getValue().contains("return_recovery_outcome"));
        assertTrue(sql.getValue().contains("return_recovery_resolved_by"));
    }

    @Test
    void paidRestoreFailsClosedWhenExactArchivedSourceIsMissing() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        OrderArchiveRestoreRepository repository = new OrderArchiveRestoreRepository(jdbc);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", 42L)
                .addValue("targetStatusId", 7L);

        when(jdbc.queryForObject(contains("order_statuses target_status"), eq(params), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(contains("FROM archive_payment_check apc"), eq(params), eq(Long.class)))
                .thenReturn(List.of(77L));
        when(jdbc.queryForObject(contains("return_recovery_outcome = 'MANUAL_RECONCILIATION'"),
                any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForObject(contains("WHERE archived.id = :sourceId"),
                any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(0);

        assertThrows(
                ArchiveRestoreConflictException.class,
                () -> repository.restoreExactLinkedPaymentSource(params)
        );
        verify(jdbc, never()).update(any(String.class), any(MapSqlParameterSource.class));
    }

    @Test
    void paidRestoreFailsClosedForArchivedManualReturnReconciliation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        OrderArchiveRestoreRepository repository = new OrderArchiveRestoreRepository(jdbc);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", 42L)
                .addValue("targetStatusId", 7L);

        when(jdbc.queryForObject(contains("order_statuses target_status"), eq(params), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(contains("FROM archive_payment_check apc"), eq(params), eq(Long.class)))
                .thenReturn(List.of(77L));
        when(jdbc.queryForObject(contains("return_recovery_outcome = 'MANUAL_RECONCILIATION'"),
                any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1);

        assertThrows(
                ArchiveRestoreConflictException.class,
                () -> repository.restoreExactLinkedPaymentSource(params)
        );
        verify(jdbc, never()).update(any(String.class), any(MapSqlParameterSource.class));
    }
}
