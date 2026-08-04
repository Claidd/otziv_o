package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PaymentRouteTerminalProvenanceMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_215__payment_route_terminal_provenance.sql";

    @Test
    void migrationAddsDurablePaymentSourceAndProviderTerminalEvidence() throws Exception {
        String sql = sql();

        assertThat(sql)
                .contains("alter table common_invoice_orders")
                .contains("add column source_payment_link_id bigint null")
                .contains("idx_common_invoice_orders_source_payment_link (source_payment_link_id)")
                .contains("alter table payment_links")
                .contains("add column provider_terminal_status varchar(32) null");
    }

    @Test
    void migrationDoesNotGuessLegacyProvenanceOrRewritePaymentState() throws Exception {
        String sql = sql();

        assertThat(sql)
                .doesNotContain("update ")
                .doesNotContain("insert ")
                .doesNotContain("delete ")
                .doesNotContain("source_payment_link_id =")
                .doesNotContain("provider_terminal_status =");
    }

    private String sql() throws Exception {
        return new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
    }
}
