package com.hunt.otziv.payments.service;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentIssueResolutionEvidenceReaderTest {
    @Mock NamedParameterJdbcTemplate jdbc;

    @Test
    void paidOrder27202NeedsOneActiveCheckAndOneExactlyMatchingConfirmedLink() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLink(8_477L, "CONFIRMED", 200_000L, true, false);
        noOtherIssues();

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertTrue(result.confirmed());
        assertEquals(8_477L, result.paymentLinkId());
    }

    @Test
    void archivedOrderNeedsArchivedCheckButMayRetainItsOnlyLinkInLiveTable() throws Exception {
        archivedPaidOrder();
        archivedCheck(200_000L, 10L, 8_477L);
        liveLink(8_477L, "CONFIRMED", 200_000L, true, false);
        noOtherIssues();

        assertTrue(new PaymentIssueResolutionEvidenceReader(jdbc)
                .confirmedPaidOrder(27_202L).confirmed());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).query(sql.capture(), anyMap(), any(RowMapper.class));
        String archiveLinkSql = sql.getAllValues().stream()
                .filter(statement -> statement.contains("FROM archive_payment_links"))
                .findFirst().orElseThrow();
        assertTrue(archiveLinkSql.contains("return_recovery_processed_at"));
        assertTrue(archiveLinkSql.contains("return_recovery_resolution_reason"));
        assertFalse(archiveLinkSql.contains("bank_cancel_"));
        assertFalse(archiveLinkSql.contains("provider_terminal_status"));
    }

    @Test
    void amountMismatchKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLink(8_477L, "CONFIRMED", 140_000L, true, false);

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("confirmed_link_missing_or_amount_mismatch", result.reason());
    }

    @Test
    void checkWithoutPaidCountSnapshotKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 0L, 8_477L);

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("check_without_settlement_snapshot", result.reason());
    }

    @Test
    void oldCheckWithoutPaymentLinkOrPaidAmountStillKeepsCardOpen() throws Exception {
        livePaidOrder();
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("check_kopecks")).thenReturn(200_000L);
        when(row.wasNull()).thenReturn(true, true);
        stubChecks("FROM payment_check", row);
        when(jdbc.query(contains("FROM archive_payment_check"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("check_without_settlement_snapshot", result.reason());
    }

    @Test
    void linkWithoutPaidTimestampKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLink(8_477L, "CONFIRMED", 200_000L, false, false);

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("confirmed_link_missing_or_amount_mismatch", result.reason());
    }

    @Test
    void returnMarkerKeepsCardOpenEvenWhenOrderAndLinkStillSayPaid() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLink(8_477L, "CONFIRMED", 200_000L, true, true);

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("payment_return_or_cancel_marker", result.reason());
    }

    @Test
    void pendingReturnOutboxKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLink(8_477L, "CONFIRMED", 200_000L, true, false);
        when(jdbc.queryForObject(contains("FROM payment_link_return_reconciliation_outbox"),
                anyMap(), eq(Long.class))).thenReturn(1L);

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("payment_return_outbox", result.reason());
    }

    @Test
    void commonInvoiceRouteKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLink(8_477L, "CONFIRMED", 200_000L, true, false);
        when(jdbc.queryForObject(contains("FROM payment_link_return_reconciliation_outbox"),
                anyMap(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("FROM common_invoice_orders"),
                anyMap(), eq(Long.class))).thenReturn(1L);

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("common_invoice_route", result.reason());
    }

    @Test
    void secondLinkKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(8_477L);
        when(row.getString("status")).thenReturn("CONFIRMED");
        when(row.getLong("confirmed_amount_kopecks")).thenReturn(200_000L);
        when(row.getBoolean("has_paid_at")).thenReturn(true);
        when(jdbc.query(contains("FROM payment_links"), anyMap(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });
        when(jdbc.query(contains("FROM archive_payment_links"), anyMap(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("link_missing_or_ambiguous", result.reason());
    }

    @Test
    void oneCheckBoundConfirmedLinkAmongUnpaidExpiredRetriesIsEnough() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLinks(
                linkRow(8_475L, "EXPIRED", null, false, false),
                linkRow(8_476L, "CANCELED", null, false, false),
                linkRow(8_477L, "CONFIRMED", 200_000L, true, false));
        noOtherIssues();

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertTrue(result.confirmed());
        assertEquals(8_477L, result.paymentLinkId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).queryForObject(contains("FROM payment_link_return_reconciliation_outbox"),
                params.capture(), eq(Long.class));
        assertEquals(List.of(8_475L, 8_476L, 8_477L), params.getValue().get("linkIds"));
    }

    @Test
    void secondConfirmedLinkKeepsCardOpenEvenWhenCheckReferencesOne() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLinks(linkRow(8_476L, "CONFIRMED", 200_000L, true, false),
                linkRow(8_477L, "CONFIRMED", 200_000L, true, false));

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("link_missing_or_ambiguous", result.reason());
    }

    @Test
    void pendingRetryKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLinks(linkRow(8_476L, "INITIATED", null, false, false),
                linkRow(8_477L, "CONFIRMED", 200_000L, true, false));

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("link_missing_or_ambiguous", result.reason());
    }

    @Test
    void expiredRetryWithPaidSnapshotKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLinks(linkRow(8_476L, "EXPIRED", 100_000L, false, false),
                linkRow(8_477L, "CONFIRMED", 200_000L, true, false));

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("link_missing_or_ambiguous", result.reason());
    }

    @Test
    void returnMarkerOnExpiredRetryKeepsCardOpen() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLinks(linkRow(8_476L, "EXPIRED", null, false, true),
                linkRow(8_477L, "CONFIRMED", 200_000L, true, false));

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertFalse(result.confirmed());
        assertEquals("link_missing_or_ambiguous", result.reason());
    }

    @Test
    void liveAndNonRestoredArchivedOrderCopiesAreAmbiguous() {
        when(jdbc.queryForList(contains("SELECT order_id FROM orders"), anyMap(), eq(Long.class)))
                .thenReturn(List.of(27_202L));
        when(jdbc.queryForList(contains("FROM orders"), anyMap(), eq(String.class)))
                .thenReturn(List.of("Оплачено"));
        when(jdbc.queryForList(contains("FROM archive_orders"), anyMap(), eq(String.class)))
                .thenReturn(List.of("Оплачено"));

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertEquals("order_missing_or_ambiguous", result.reason());
    }

    @Test
    void restoredArchiveCopyDoesNotDuplicateLiveOrder() throws Exception {
        livePaidOrder();
        liveCheck(200_000L, 10L, 8_477L);
        liveLink(8_477L, "CONFIRMED", 200_000L, true, false);
        noOtherIssues();

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertTrue(result.confirmed());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).queryForList(sql.capture(), anyMap(), eq(String.class));
        String archiveOrderSql = sql.getAllValues().stream()
                .filter(statement -> statement.contains("FROM archive_orders"))
                .findFirst().orElseThrow();
        assertTrue(archiveOrderSql.contains("base_order.restored_at IS NULL"));
    }

    @Test
    void liveAndArchivedActiveChecksAreAmbiguous() throws Exception {
        livePaidOrder();
        stubCheck("FROM payment_check", 200_000L, 10L, 8_477L);
        stubCheck("FROM archive_payment_check", 200_000L, 10L, 8_477L);

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_202L);

        assertEquals("check_missing_or_ambiguous", result.reason());
    }

    @Test
    void unpaidBanOrderStopsBeforeReadingAnyCheckOrLink() {
        when(jdbc.queryForList(contains("SELECT order_id FROM orders"), anyMap(), eq(Long.class)))
                .thenReturn(List.of(27_203L));
        when(jdbc.queryForList(contains("FROM orders"), anyMap(), eq(String.class)))
                .thenReturn(List.of("Бан"));
        when(jdbc.queryForList(contains("FROM archive_orders"), anyMap(), eq(String.class)))
                .thenReturn(List.of());

        var result = new PaymentIssueResolutionEvidenceReader(jdbc).confirmedPaidOrder(27_203L);

        assertFalse(result.confirmed());
        assertEquals("order_not_paid", result.reason());
    }

    private void livePaidOrder() {
        when(jdbc.queryForList(contains("SELECT order_id FROM orders"), anyMap(), eq(Long.class)))
                .thenReturn(List.of(27_202L));
        when(jdbc.queryForList(contains("FROM orders"), anyMap(), eq(String.class)))
                .thenReturn(List.of("Оплачено"));
        when(jdbc.queryForList(contains("FROM archive_orders"), anyMap(), eq(String.class)))
                .thenReturn(List.of());
    }

    private void archivedPaidOrder() {
        when(jdbc.queryForList(contains("SELECT order_id FROM orders"), anyMap(), eq(Long.class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("SELECT order_id FROM archive_orders"), anyMap(), eq(Long.class)))
                .thenReturn(List.of(27_202L));
        when(jdbc.queryForList(contains("FROM orders"), anyMap(), eq(String.class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM archive_orders"), anyMap(), eq(String.class)))
                .thenReturn(List.of("Оплачено"));
    }

    private void liveCheck(long amount, long paidCount, long linkId) throws Exception {
        stubCheck("FROM payment_check", amount, paidCount, linkId);
        when(jdbc.query(contains("FROM archive_payment_check"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    private void archivedCheck(long amount, long paidCount, long linkId) throws Exception {
        stubCheck("FROM archive_payment_check", amount, paidCount, linkId);
        when(jdbc.query(contains("FROM payment_check"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubCheck(String table, long amount, long paidCount, long linkId) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("check_kopecks")).thenReturn(amount);
        when(row.getLong("check_paid_amount")).thenReturn(paidCount);
        when(row.getLong("check_payment_link")).thenReturn(linkId);
        stubChecks(table, row);
    }

    private void stubChecks(String table, ResultSet row) {
        when(jdbc.query(contains(table), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(row, 0));
        });
    }

    @SuppressWarnings("unchecked")
    private void liveLink(long id, String status, long amount, boolean paidAt,
                          boolean returnMarker) throws Exception {
        liveLinks(linkRow(id, status, amount, paidAt, returnMarker));
    }

    private ResultSet linkRow(long id, String status, Long amount, boolean paidAt,
                              boolean returnMarker) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("status")).thenReturn(status);
        when(row.getLong("confirmed_amount_kopecks")).thenReturn(amount == null ? 0L : amount);
        when(row.wasNull()).thenReturn(amount == null);
        when(row.getBoolean("has_paid_at")).thenReturn(paidAt);
        when(row.getBoolean("has_return_or_cancel_markers")).thenReturn(returnMarker);
        return row;
    }

    private void liveLinks(ResultSet... rows) {
        when(jdbc.query(contains("FROM payment_links"), anyMap(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    List<Object> mapped = new ArrayList<>();
                    for (int index = 0; index < rows.length; index++) {
                        mapped.add(mapper.mapRow(rows[index], index));
                    }
                    return mapped;
                });
        when(jdbc.query(contains("FROM archive_payment_links"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    private void noOtherIssues() {
        when(jdbc.queryForObject(contains("FROM payment_link_return_reconciliation_outbox"),
                anyMap(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("FROM common_invoice_orders"),
                anyMap(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("FROM personal_reminders"),
                anyMap(), eq(Long.class))).thenReturn(0L);
    }
}
