package com.hunt.otziv.common_billing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.archive.dto.ArchiveAccessScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class CommonInvoiceArchiveRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void countKeepsWhitespaceBetweenIdentifierAndFromClause() {
        when(jdbc.queryForObject(
                anyString(),
                any(MapSqlParameterSource.class),
                eq(Long.class)
        )).thenReturn(0L);
        CommonInvoiceArchiveRepository repository = new CommonInvoiceArchiveRepository(jdbc);

        repository.count(ArchiveAccessScope.all(), "");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(
                sqlCaptor.capture(),
                any(MapSqlParameterSource.class),
                eq(Long.class)
        );
        String sql = sqlCaptor.getValue().replace("\r\n", "\n");

        assertThat(sql)
                .doesNotContain("invoice_idFROM")
                .contains("DISTINCT ci.invoice_id\nFROM common_invoices ci")
                .contains("DISTINCT aci.invoice_id\nFROM archive_common_invoices aci");
    }

    @Test
    void findKeepsWhitespaceBetweenSortDirectionAndLimit() {
        CommonInvoiceArchiveRepository repository = new CommonInvoiceArchiveRepository(jdbc);

        repository.find(ArchiveAccessScope.all(), "", 0, 50, "desc");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sqlCaptor.capture(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        );
        String sql = sqlCaptor.getValue().replace("\r\n", "\n");

        assertThat(sql)
                .doesNotContain("DESCLIMIT")
                .contains(", invoice_id DESC\nLIMIT :limit OFFSET :offset");
    }

    @Test
    void lockedRestoreRecheckAcceptsOnlyTerminalArchivedPaymentRefs() {
        Map<String, Long> params = Map.of("invoiceId", 40L);
        when(jdbc.queryForList(contains("FROM archive_common_invoices"), eq(params), eq(Long.class)))
                .thenReturn(List.of(40L));
        when(jdbc.queryForList(
                contains("FROM archive_common_invoice_payment_refs"),
                eq(params),
                eq(String.class)
        )).thenReturn(List.of("APPLIED", " partial_refunded ", "REVERSED"));
        CommonInvoiceArchiveRepository repository = new CommonInvoiceArchiveRepository(jdbc);

        assertThat(repository.lockAndCheckPaymentRefsRestorable(40L)).isTrue();
    }

    @Test
    void lockedRestoreRecheckRejectsCurrentAndUnknownPaymentRefs() {
        Map<String, Long> params = Map.of("invoiceId", 40L);
        when(jdbc.queryForList(contains("FROM archive_common_invoices"), eq(params), eq(Long.class)))
                .thenReturn(List.of(40L));
        when(jdbc.queryForList(
                contains("FROM archive_common_invoice_payment_refs"),
                eq(params),
                eq(String.class)
        )).thenReturn(List.of("CURRENT", "FUTURE_PROVIDER_STATE"));
        CommonInvoiceArchiveRepository repository = new CommonInvoiceArchiveRepository(jdbc);

        assertThat(repository.lockAndCheckPaymentRefsRestorable(40L)).isFalse();
    }

    @Test
    void lockedRestoreRecheckRejectsAnAlreadyRestoredInvoiceBeforeReadingPaymentRefs() {
        Map<String, Long> params = Map.of("invoiceId", 40L);
        when(jdbc.queryForList(contains("FROM archive_common_invoices"), eq(params), eq(Long.class)))
                .thenReturn(List.of());
        CommonInvoiceArchiveRepository repository = new CommonInvoiceArchiveRepository(jdbc);

        assertThat(repository.lockAndCheckPaymentRefsRestorable(40L)).isFalse();
        verify(jdbc, never()).queryForList(
                contains("FROM archive_common_invoice_payment_refs"),
                eq(params),
                eq(String.class)
        );
    }
}
