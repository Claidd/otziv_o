package com.hunt.otziv.common_billing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.archive.dto.ArchiveAccessScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
}
