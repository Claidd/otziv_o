package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractorRoutingDecisionMigrationContractTest {

    @Test
    void migrationPersistsDecisionTraceAndBackfillsLegacyOwnerFallback() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V1_10_225__contractor_routing_decision_reasons.sql"
        ));

        assertThat(migration)
                .contains("ALTER TABLE contractor_payment_allocations")
                .contains("ALTER TABLE contractor_payment_allocation_events")
                .contains("routing_decision_reason VARCHAR(64)")
                .contains("specialist_rejection_reason VARCHAR(64)")
                .contains("manager_rejection_reason VARCHAR(64)")
                .contains("SET routing_decision_reason = 'LEGACY_UNCLASSIFIED'")
                .contains("idx_contractor_allocations_routing_reason");
    }
}
