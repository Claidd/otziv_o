package com.hunt.otziv.payments.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import java.sql.ResultSet;
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
import org.springframework.jdbc.core.RowMapper;

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
        assertTrue(copySql.getValue().contains("contractor_allocation_id"));
        assertTrue(copySql.getValue().contains("shadow_route_generation"));
        assertTrue(copySql.getValue().contains("contractor_evidence_original_link_id"));
        assertTrue(copySql.getValue().contains("manual_bank_name"));
        assertTrue(copySql.getValue().contains("return_recovery_processed_at"));
        assertTrue(copySql.getValue().contains("return_recovery_payment_check_id"));
        assertTrue(copySql.getValue().contains("return_recovery_outcome"));
        assertTrue(copySql.getValue().contains("return_recovery_resolved_at"));
        assertTrue(copySql.getValue().contains("return_recovery_resolved_by"));
        assertTrue(copySql.getValue().contains("return_recovery_resolution_reason"));

        ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(deleteSql.capture(), anyMap());
        assertArchiveFinalFence(deleteSql.getValue());
        assertTrue(deleteSql.getValue().contains("archive_payment_links"));
        assertTrue(deleteSql.getValue().contains("SELECT DISTINCT"));
        assertTrue(deleteSql.getValue().contains("FROM payment_links contractor_evidence_source"));
        assertTrue(deleteSql.getValue().contains("contractor_evidence_source.id IN (:ids)"));
        assertTrue(deleteSql.getValue().contains(
                "contractor_evidence_source.contractor_evidence_original_link_id IN (:ids)"
        ));
        assertFalse(deleteSql.getValue().contains("FROM payment_links contractor_evidence\n"));
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    void archivedContractorSearchNeverUsesScrubbedPlaintextPii() {
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenReturn(List.of());

        repository.findArchivedPage(
                0,
                20,
                "all",
                "получатель",
                null,
                null,
                null,
                true,
                "https://example.ru"
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        );
        assertTrue(sql.getValue().contains(
                "COALESCE(apl.manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'"
        ));
        assertTrue(sql.getValue().contains("LOWER(COALESCE(apl.manual_comment, '')) LIKE :searchText"));
        assertTrue(sql.getValue().contains("allocation.id = apl.contractor_allocation_id"));
        assertTrue(sql.getValue().contains("recipient_role.name IN ('ROLE_ADMIN', 'ROLE_OWNER')"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void archivedContractorResponseRedactsEveryLegacyPlaintextRecipientField() throws Exception {
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenAnswer(invocation -> {
            RowMapper<AdminPaymentLinkResponse> mapper = invocation.getArgument(2);
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            when(rs.getLong(anyString())).thenAnswer(valueInvocation -> switch (
                    valueInvocation.getArgument(0, String.class)
            ) {
                case "id" -> 42L;
                case "amount_kopecks" -> 100_00L;
                default -> 0L;
            });
            when(rs.getString(anyString())).thenAnswer(valueInvocation -> switch (
                    valueInvocation.getArgument(0, String.class)
            ) {
                case "token" -> "archived-token";
                case "manual_source" -> "CONTRACTOR_PAYMENT_PROFILE";
                case "manual_phone" -> "+79990000000";
                case "manual_recipient_name" -> "Получатель";
                case "manual_bank_name" -> "Банк";
                case "manual_payment_url" -> "https://example.ru/private";
                case "manual_comment" -> "Секретный комментарий";
                default -> null;
            });
            return List.of(mapper.mapRow(rs, 0));
        });

        List<AdminPaymentLinkResponse> page = repository.findArchivedPage(
                0,
                20,
                "all",
                "",
                null,
                null,
                null,
                false,
                "https://example.ru"
        );

        AdminPaymentLinkResponse response = page.get(0);
        assertEquals("", response.manualPhone());
        assertEquals("", response.manualRecipientName());
        assertEquals("", response.manualTaskTitle());
        assertEquals("", response.manualBankName());
        assertEquals("", response.manualPaymentUrl());
        assertEquals("", response.manualComment());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void archivedTochkaResponseUsesTheFrozenProviderUrlPolicy() throws Exception {
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenAnswer(invocation -> {
            RowMapper<AdminPaymentLinkResponse> mapper = invocation.getArgument(2);
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            when(rs.getLong(anyString())).thenAnswer(valueInvocation -> switch (
                    valueInvocation.getArgument(0, String.class)
            ) {
                case "id" -> 42L;
                case "amount_kopecks" -> 100_00L;
                default -> 0L;
            });
            when(rs.getString(anyString())).thenAnswer(valueInvocation -> switch (
                    valueInvocation.getArgument(0, String.class)
            ) {
                case "token" -> "archived-tochka-token";
                case "payment_profile_provider" -> "TOCHKA";
                case "payment_url" -> "https://merch.securepaytb.ru/payments/operation";
                default -> null;
            });
            return List.of(mapper.mapRow(rs, 0));
        });

        List<AdminPaymentLinkResponse> page = repository.findArchivedPage(
                0, 20, "all", "", null, null, null, false, "https://example.ru"
        );

        assertEquals("https://merch.securepaytb.ru/payments/operation", page.get(0).paymentUrl());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("profile.provider AS payment_profile_provider"));
    }

    @Test
    void hardDeleteAndPreparedOrderGuardsUseTheFullArchiveFence() {
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);

        repository.hasLiveArchiveBlockerForOrder(42L);
        repository.hasPreparedOrderArchiveBlocker();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .queryForObject(sql.capture(), anyMap(), eq(Long.class));
        assertArchiveFinalFence(sql.getAllValues().get(0));
        assertPreparedArchiveFinalFence(sql.getAllValues().get(1));
    }

    private static void assertArchiveSelectionFence(String sql) {
        assertSqlTokensSeparated(sql);
        assertArchiveStateFence(sql);
        assertTrue(sql.contains("active_payment_check.check_payment_link = pl.id"));
        assertTrue(sql.contains("payment_success_notification_retry_claims"));
        assertTrue(sql.contains("notification_claim.processing_lease_until > CURRENT_TIMESTAMP(6)"));
        assertTrue(sql.contains("contractor_payment_allocations contractor_allocation"));
        assertTrue(sql.contains("contractor_allocation.reconcile_claim_token IS NOT NULL"));
        assertTrue(sql.contains("contractor_allocation.last_reconciled_at IS NULL"));
        assertTrue(sql.contains("pl.updated_at > contractor_allocation.last_reconciled_at"));
        assertTrue(sql.contains("contractor_allocation.needs_return_amount = TRUE"));
        assertTrue(sql.contains("contractor_allocation.status NOT IN"));
        assertTrue(sql.contains("prepared_shadow.source_generation_snapshot = pl.shadow_route_generation"));
        assertTrue(sql.contains("contractor_evidence_original_link_id"));
        assertTrue(sql.contains("MANUAL_EVIDENCE:"));
    }

    private static void assertArchiveFinalFence(String sql) {
        assertSqlTokensSeparated(sql);
        assertArchiveStateFence(sql);
        assertTrue(sql.contains("active_payment_check.check_payment_link = pl.id"));
        assertTrue(sql.contains("payment_success_notification_retry_claims"));
        assertFalse(sql.contains("notification_claim.processing_lease_until"));
        assertTrue(sql.contains("contractor_payment_allocations contractor_allocation"));
        assertTrue(sql.contains("contractor_evidence_original_link_id"));
        assertTrue(sql.contains("source_generation_snapshot"));
    }

    private static void assertPreparedArchiveFinalFence(String sql) {
        assertSqlTokensSeparated(sql);
        assertArchiveStateFence(sql);
        assertFalse(sql.contains("active_payment_check.check_payment_link = pl.id"));
        assertTrue(sql.contains("payment_success_notification_retry_claims"));
        assertFalse(sql.contains("notification_claim.processing_lease_until"));
        assertTrue(sql.contains("contractor_payment_allocations contractor_allocation"));
        assertTrue(sql.contains("contractor_evidence_original_link_id"));
        assertTrue(sql.contains("source_generation_snapshot"));
    }

    private static void assertArchiveStateFence(String sql) {
        assertTrue(sql.contains("status = 'NEEDS_RECONCILIATION'"));
        assertTrue(sql.contains("bank_init_nonce IS NOT NULL"));
        assertTrue(sql.contains("bank_cancel_nonce IS NOT NULL"));
        assertTrue(sql.contains("bank_cancel_origin_status IS NOT NULL"));
        assertTrue(sql.contains("return_recovery_outcome, '') = 'MANUAL_RECONCILIATION'"));
        assertTrue(sql.contains("return_recovery_processed_at IS NULL"));
        assertTrue(sql.contains("'APPLIED_MANUALLY', 'ACCEPTED_NOOP'"));
        assertTrue(sql.contains(
                "LOWER(TRIM(COALESCE(pl.last_error, ''))) LIKE 'manual_card_payment_pending:%'"
        ));
        assertFalse(sql.contains("manual_card_payment_completed:"));
        assertTrue(sql.contains("COALESCE(pl.status, '') NOT IN"));
        assertTrue(sql.contains("receipt_status"));
        assertTrue(sql.contains("payment_success_notification_retry_eligible"));
    }

    private static void assertSqlTokensSeparated(String sql) {
        assertFalse(sql.contains("AND NOT("));
        assertFalse(sql.contains("OR("));
        assertFalse(sql.contains("OREXISTS"));
    }
}
