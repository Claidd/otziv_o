package com.hunt.otziv.payments.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentLinkArchiveRepositoryContractTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private PaymentLinkArchiveRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PaymentLinkArchiveRepository(jdbc);
    }

    @Test
    void autoCandidateSnapshotIsReadOnlyAndExcludesLiveOrRetryableSideEffectMarkers() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of());

        repository.findArchiveCandidateIds(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                100
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        assertArchiveSelectionFence(sql.getValue());
        assertFalse(sql.getValue().contains("FOR UPDATE"));
    }

    @Test
    void autoArchiveLocksOrdersBeforeRevalidatingAndLockingPaymentLinks() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(Long.class)))
                .thenReturn(List.of(20L));
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(10L));

        repository.findOrderIdsForPaymentLinkIds(List.of(10L));
        repository.lockOrderIdsForArchive(List.of(20L));
        repository.findArchiveCandidateIdsForUpdate(
                List.of(10L),
                List.of(20L),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0)
        );

        ArgumentCaptor<String> parentSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .queryForList(parentSql.capture(), anyMap(), eq(Long.class));
        assertFalse(parentSql.getAllValues().get(0).contains("FOR UPDATE"));
        assertTrue(parentSql.getAllValues().get(1).contains("FROM orders"));
        assertTrue(parentSql.getAllValues().get(1).contains("ORDER BY o.order_id"));
        assertTrue(parentSql.getAllValues().get(1).contains("FOR UPDATE"));

        ArgumentCaptor<String> linkSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(linkSql.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        assertArchiveSelectionFence(linkSql.getValue());
        assertTrue(linkSql.getValue().contains("ORDER BY pl.order_id, pl.id"));
        assertTrue(linkSql.getValue().contains("FOR UPDATE SKIP LOCKED"));
    }

    @Test
    void copyAndDeleteRepeatTheSameArchiveFence() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        repository.archiveIds(List.of(10L), LocalDateTime.of(2026, 1, 1, 0, 0), "test", 7L);
        repository.deleteLiveIds(List.of(10L));

        ArgumentCaptor<String> copySql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(copySql.capture(), any(MapSqlParameterSource.class));
        assertArchiveFinalFence(copySql.getValue());

        ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(deleteSql.capture(), anyMap());
        assertArchiveFinalFence(deleteSql.getValue());
        assertTrue(deleteSql.getValue().contains("archive_payment_links"));
    }

    @Test
    void expiredClaimCleanupIsFencedByLeaseAndIneligiblePaymentState() {
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        repository.deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(List.of(10L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), anyMap());
        String cleanup = sql.getValue();
        assertTrue(cleanup.contains("processing_lease_until <= CURRENT_TIMESTAMP(6)"));
        assertTrue(cleanup.contains("pl.status = 'CONFIRMED'"));
        assertTrue(cleanup.contains("pl.payment_success_notified_at IS NULL"));
        assertTrue(cleanup.contains("payment_success_notification_retry_eligible"));
        assertTrue(cleanup.contains("pl.id IN (:paymentLinkIds)"));
    }

    @Test
    void hardDeleteAndPreparedOrderGuardsUseTheFullArchiveFence() {
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);

        repository.hasLiveArchiveBlockerForOrder(42L);
        repository.hasPreparedOrderArchiveBlocker();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .queryForObject(sql.capture(), anyMap(), eq(Long.class));
        sql.getAllValues().forEach(PaymentLinkArchiveRepositoryContractTest::assertArchiveFinalFence);
    }

    private static void assertArchiveSelectionFence(String sql) {
        assertArchiveStateFence(sql);
        assertTrue(sql.contains("payment_success_notification_retry_claims"));
        assertTrue(sql.contains("notification_claim.processing_lease_until > CURRENT_TIMESTAMP(6)"));
    }

    private static void assertArchiveFinalFence(String sql) {
        assertArchiveStateFence(sql);
        assertTrue(sql.contains("payment_success_notification_retry_claims"));
        assertFalse(sql.contains("notification_claim.processing_lease_until"));
    }

    private static void assertArchiveStateFence(String sql) {
        assertTrue(sql.contains("status = 'NEEDS_RECONCILIATION'"));
        assertTrue(sql.contains("bank_init_nonce IS NOT NULL"));
        assertTrue(sql.contains("bank_cancel_nonce IS NOT NULL"));
        assertTrue(sql.contains("bank_cancel_origin_status IS NOT NULL"));
        assertTrue(sql.contains("COALESCE(pl.status, '') NOT IN"));
        assertTrue(sql.contains("receipt_status"));
        assertTrue(sql.contains("payment_success_notification_retry_eligible"));
    }
}
