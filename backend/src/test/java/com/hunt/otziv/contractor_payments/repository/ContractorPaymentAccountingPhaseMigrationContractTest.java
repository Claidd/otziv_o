package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContractorPaymentAccountingPhaseMigrationContractTest {

    @Test
    void migrationInitializesOneIrreversiblePhaseFromExistingLiveAccounting() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V1_10_223__contractor_payment_accounting_phase.sql"
        )) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("id INT PRIMARY KEY")
                .contains("CHECK (id = 1)")
                .contains("phase IN ('SHADOW', 'LIVE')")
                .contains("WHERE allocation.mode = 'LIVE'")
                .doesNotContain("UPDATE contractor_payment_accounting_phase");
    }
}
