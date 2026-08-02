package com.hunt.otziv.archive.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
        assertTrue(contract.contains("payment_success_notification_retry_claims"));
        assertTrue(contract.contains("notification_claim.processing_lease_until > CURRENT_TIMESTAMP(6)"));
        assertTrue(contract.contains("COALESCE(pl.status, '') NOT IN"));
        assertTrue(contract.contains("UPPER(TRIM(COALESCE(ref.status, ''))) NOT IN"));
        assertTrue(contract.contains("'APPLIED'"));
        assertTrue(contract.contains("'PARTIAL_REFUNDED'"));
        assertTrue(contract.contains("'PARTIAL_REVERSED'"));
        assertTrue(contract.contains("candidate_order.order_id IS NULL"));
    }
}
