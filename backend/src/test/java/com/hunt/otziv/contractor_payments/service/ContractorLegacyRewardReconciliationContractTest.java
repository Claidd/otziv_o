package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.contractor_payments.repository.ContractorCompletionCutoverPreflightRepository;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ContractorLegacyRewardReconciliationContractTest {

    @Test
    void migrationCreatesOnlyAuditedSnapshotTablesAndNeverRewritesFinancialRows() throws Exception {
        String sql = new String(
                java.util.Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(
                        "db/migration/V1_10_249__contractor_legacy_reward_reconciliation.sql"
                )).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
        ).toUpperCase(Locale.ROOT);

        assertThat(sql)
                .contains(
                        "CONTRACTOR_LEGACY_REWARD_RECONCILIATION_RUNS",
                        "CONTRACTOR_LEGACY_REWARD_RECONCILIATION_ITEMS",
                        "RECONCILIATION_SNAPSHOT_HASH",
                        "RECONCILIATION_GROUP_HASH",
                        "MANUAL_EVIDENCE_REFERENCE",
                        "TARGET_ZP_CONTRACTOR_ROLE"
                )
                .doesNotContain("UPDATE ZP", "DELETE FROM ZP", "INSERT INTO ZP");
    }

    @Test
    void preflightAcceptsOnlySignedExactManualEvidenceBeforeCutoff() throws Exception {
        Query query = ContractorCompletionCutoverPreflightRepository.class
                .getMethod("countActiveLegacyRewardCutoverConflicts", LocalDate.class)
                .getAnnotation(Query.class);
        String sql = query.value().replaceAll("\s+", " ").toUpperCase(Locale.ROOT);

        assertThat(sql).contains(
                "RECONCILIATION_KIND = 'MANUAL'",
                "RECONCILIATION_STATUS = 'APPLIED'",
                "MANUAL_COMPLETED_ON < :STARTDATE",
                "MANUAL_EVIDENCE_REFERENCE",
                "RESOLUTION_REASON",
                "ORIGINAL_ZP_USER",
                "TARGET_ZP_SOURCE",
                "TARGET_ZP_CONTRACTOR_ROLE",
                "ZP_ATTRIBUTION_FINAL",
                "COUNT(*) FROM ZP ACTIVE_EXACT"
        );
    }

    @Test
    void automaticClassificationRequiresRewardAndReviewEvidenceBeforeBoundary() throws Exception {
        java.nio.file.Path repositoryPath = java.nio.file.Path.of(
                "src/main/java/com/hunt/otziv/contractor_payments/repository/"
                        + "ContractorLegacyRewardReconciliationRepository.java"
        );
        if (!java.nio.file.Files.exists(repositoryPath)) {
            repositoryPath = java.nio.file.Path.of("backend").resolve(repositoryPath);
        }
        String repository = java.nio.file.Files.readString(repositoryPath).toUpperCase(Locale.ROOT);

        assertThat(repository).contains(
                "Z.ZP_DATE < :STARTDATE",
                "R2.REVIEW_PUBLISH_DATE >= :STARTDATE",
                "BAD_REVIEW_TASK_COMPLETED_DATE >= :STARTDATE",
                "REVIEW_RECOVERY_TASK_STATUS = 'PLANNED'",
                "FOR UPDATE",
                "ZP_UPDATED_AT <=> :UPDATEDAT",
                "SHA2(COALESCE(ZP_ATTRIBUTION_SNAPSHOT, ''), 256)"
        );
        assertThat(repository)
                .contains(
                        "OVER (PARTITION BY CANDIDATE.ZP_ORDER)",
                        "GROUP_REQUIRES_RECONCILIATION = 1"
                )
                .doesNotContain("WHERE Z.ZP_ACTIVE = 1 AND Z.ZP_ORDER > 0 AND ( Z.ZP_SOURCE");

        java.nio.file.Path servicePath = repositoryPath.getParent().getParent()
                .resolve("service/ContractorLegacyRewardReconciliationService.java");
        String service = java.nio.file.Files.readString(servicePath).toUpperCase(Locale.ROOT);
        assertThat(service)
                .contains(
                        "LEGACY_PERFORMER_PRODUCT.EQUALS(ROW.SOURCE())",
                        "EXPECTED.EQUALS(ROW.SOURCE())",
                        "ISCOMPLETIONBASED(ROW.SOURCE())",
                        "ISLEDGERSOURCECOMPATIBLE(ROW.SOURCE(), ROLE)",
                        "RETURN NEW TARGET(NULL, NULL)"
                );
    }
}
