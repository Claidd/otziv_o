package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class WorkerRiskIncidentMigrationContractTest {

    private static final String IMMUTABLE_ROW_VERSION_MIGRATION =
            "db/migration/V1_10_191__worker_risk_incident_row_version.sql";
    private static final String SLA_DELIVERY_CLAIM_MIGRATION =
            "db/migration/V1_10_193__worker_risk_sla_delivery_claim.sql";

    @Test
    void shippedRowVersionMigrationKeepsItsFlywayChecksum() throws Exception {
        String sql = migration(IMMUTABLE_ROW_VERSION_MIGRATION);

        assertThat(flywayChecksum(sql)).isEqualTo(1_803_252_389L);
        assertThat(sql).containsOnlyOnce("ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0");
        assertThat(sql).doesNotContain("sla_delivery_claim", "idx_worker_risk_sla_cursor");
    }

    @Test
    void laterSlaDeliveryClaimChangesUseAnAdditiveOnlineMigration() throws Exception {
        String sql = migration(SLA_DELIVERY_CLAIM_MIGRATION);

        assertThat(sql).contains(
                "ALTER TABLE worker_risk_incidents",
                "ADD COLUMN sla_delivery_claim_token VARCHAR(36) NULL",
                "ADD COLUMN sla_delivery_claimed_at DATETIME(6) NULL",
                "ADD COLUMN sla_delivery_claim_kind VARCHAR(16) NULL",
                "ADD INDEX idx_worker_risk_sla_cursor (status, response_due_at, incident_id)",
                "ALGORITHM=INPLACE",
                "LOCK=NONE"
        );
        assertThat(sql.toLowerCase(Locale.ROOT))
                .doesNotContain("insert ", "update ", "delete ", "drop ");
    }

    private static String migration(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private static long flywayChecksum(String sql) {
        CRC32 checksum = new CRC32();
        sql.lines().forEach(line -> checksum.update(line.getBytes(StandardCharsets.UTF_8)));
        return checksum.getValue();
    }
}
