package com.hunt.otziv.archive.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class OrderArchiveDryRunRepositoryContractTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private OrderArchiveDryRunRepository repository;

    @BeforeEach
    void setUp() {
        repository = new OrderArchiveDryRunRepository(jdbc);
    }

    @Test
    void preparedArchiveLocksOrdersBeforeCommonInvoicesInStableOrder() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(Long.class))).thenReturn(List.of(1L));

        repository.lockPreparedCandidateOrders();
        repository.lockPreparedCandidateCommonInvoices();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForList(sql.capture(), anyMap(), eq(Long.class));
        assertTrue(sql.getAllValues().get(0).contains("FROM orders"));
        assertTrue(sql.getAllValues().get(0).contains("ORDER BY o.order_id"));
        assertTrue(sql.getAllValues().get(0).contains("FOR UPDATE"));
        assertTrue(sql.getAllValues().get(1).contains("FROM common_invoices"));
        assertTrue(sql.getAllValues().get(1).contains("ORDER BY invoice.invoice_id"));
        assertTrue(sql.getAllValues().get(1).contains("FOR UPDATE"));
    }

    @Test
    void postLockDriftCheckReplaysFullEligibilityAndMembershipContracts() {
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);

        repository.hasPreparedCandidateEligibilityDrift(LocalDate.of(2026, 1, 1));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).queryForObject(sql.capture(), anyMap(), eq(Long.class));
        String contract = String.join("\n", sql.getAllValues());
        assertTrue(contract.contains("bad_review_tasks"));
        assertTrue(contract.contains("next_order_requests"));
        assertTrue(contract.contains("common_invoice_orders"));
        assertTrue(contract.contains("bank_init_nonce IS NOT NULL"));
        assertTrue(contract.contains("bank_cancel_origin_status IS NOT NULL"));
        assertTrue(contract.contains(
                "LOWER(TRIM(COALESCE(pl.last_error, ''))) LIKE 'manual_card_payment_pending:%'"
        ));
        assertFalse(contract.contains("manual_card_payment_completed:"));
        assertTrue(contract.contains("payment_success_notification_retry_claims"));
        assertTrue(contract.contains("notification_claim.processing_lease_until > CURRENT_TIMESTAMP(6)"));
        assertTrue(contract.contains("COALESCE(pl.status, '') NOT IN"));
        assertTrue(contract.contains("UPPER(TRIM(COALESCE(ref.status, ''))) NOT IN"));
        assertTrue(contract.contains("'APPLIED'"));
        assertTrue(contract.contains("'PARTIAL_REFUNDED'"));
        assertTrue(contract.contains("'PARTIAL_REVERSED'"));
        assertEveryCommonInvoiceRefPredicateTreatsExpiredAsTerminal(contract);
        assertTrue(contract.contains("candidate_order.order_id IS NULL"));
    }

    @Test
    void preparedRouteAndManualEvidenceFencesSurviveUntilDurableAccounting() {
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);

        assertTrue(repository.hasPreparedCandidateUnmaterializedShadowRoutes());
        assertTrue(repository.hasPreparedCandidateUnrecordedContractorManualEvidence());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForObject(sql.capture(), anyMap(), eq(Long.class));
        String contract = String.join("\n", sql.getAllValues());
        assertTrue(contract.contains("source_generation_snapshot"));
        assertTrue(contract.contains("shadow_route_generation"));
        assertTrue(contract.contains("contractor_evidence_original_link_id"));
        assertTrue(contract.contains("MANUAL_EVIDENCE:"));
    }

    @Test
    void reviewArchiveCopyPersistsTheEffectiveFilialTitleSnapshot() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
                .thenReturn(List.of("review_id"));

        repository.copyPreparedCandidatesToArchive(
                11L,
                LocalDateTime.of(2026, 8, 4, 1, 0),
                "retention"
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(MapSqlParameterSource.class));
        String reviewCopy = sql.getAllValues().stream()
                .filter(value -> value.contains("INSERT IGNORE INTO archive_reviews"))
                .findFirst()
                .orElseThrow();

        assertTrue(reviewCopy.contains("review_filial_title_snapshot"));
        assertTrue(reviewCopy.contains("LEFT JOIN filial review_filial"));
        assertTrue(reviewCopy.contains("LEFT JOIN filial order_filial"));
        assertTrue(reviewCopy.contains("NULLIF(TRIM(review_filial.filial_title), '')"));
        assertTrue(reviewCopy.contains("NULLIF(TRIM(order_filial.filial_title), '')"));
        assertTrue(reviewCopy.contains(
                "WHEN r.review_filial IS NULL OR r.review_filial = o.order_filial"
        ));
        assertTrue(reviewCopy.contains("ELSE NULL"));
    }

    private void assertEveryCommonInvoiceRefPredicateTreatsExpiredAsTerminal(String sql) {
        Matcher predicates = Pattern.compile(
                "UPPER\\(TRIM\\(COALESCE\\([^)]*\\)\\)\\) NOT IN \\((.*?)\\)",
                Pattern.DOTALL
        ).matcher(sql);
        int predicateCount = 0;
        while (predicates.find()) {
            predicateCount++;
            assertTrue(predicates.group(1).contains("'EXPIRED'"));
        }
        assertTrue(predicateCount >= 2);
    }
}
