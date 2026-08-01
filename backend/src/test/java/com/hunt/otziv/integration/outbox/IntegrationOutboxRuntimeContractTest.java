package com.hunt.otziv.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class IntegrationOutboxRuntimeContractTest {

    @Test
    void mysqlClaimsUseDatabaseClockSkipLockedAndLeaseFence() {
        assertThat(IntegrationOutboxRepository.FIND_STALE_HEAD_CANDIDATES_SQL)
                .containsIgnoringCase("CURRENT_TIMESTAMP(6)")
                .containsIgnoringCase("LIMIT :candidateScanLimit")
                .doesNotContain("FOR UPDATE")
                .containsIgnoringCase("candidate.attempt_count < candidate.max_attempts");
        assertThat(IntegrationOutboxRepository.FIND_PENDING_HEAD_CANDIDATES_SQL)
                .containsIgnoringCase("CURRENT_TIMESTAMP(6)")
                .containsIgnoringCase("LIMIT :candidateScanLimit")
                .doesNotContain("FOR UPDATE")
                .containsIgnoringCase("candidate.attempt_count < candidate.max_attempts");
        assertThat(IntegrationOutboxRepository.LOCK_CANDIDATE_SQL)
                .containsIgnoringCase("integration_outbox_id = :outboxId")
                .containsIgnoringCase("FOR UPDATE SKIP LOCKED")
                .doesNotContain("NOT EXISTS");
        assertThat(IntegrationOutboxRepository.CLAIM_SQL)
                .containsIgnoringCase("processing_token = :processingToken")
                .containsIgnoringCase("event_type IN (:allowedEventTypes)")
                .containsIgnoringCase("processing_lease_until = TIMESTAMPADD")
                .containsIgnoringCase("MICROSECOND")
                .containsIgnoringCase("CURRENT_TIMESTAMP(6)");
    }

    @Test
    void claimQueriesAllowOnlyRegisteredTypesAndOnlyAggregateHeads() {
        assertSafeAggregateHeadClaim(
                IntegrationOutboxRepository.FIND_STALE_HEAD_CANDIDATES_SQL
        );
        assertSafeAggregateHeadClaim(
                IntegrationOutboxRepository.FIND_PENDING_HEAD_CANDIDATES_SQL
        );

        assertThat(IntegrationOutboxRepository.CLAIM_SQL)
                .containsIgnoringCase("event_type IN (:allowedEventTypes)");
        assertThat(IntegrationOutboxRepository.HAS_EARLIER_NON_SUCCEEDED_SQL)
                .containsIgnoringCase("earlier.aggregate_type = candidate.aggregate_type")
                .containsIgnoringCase("earlier.aggregate_id = candidate.aggregate_id")
                .containsIgnoringCase(
                        "earlier.status IN ('PENDING', 'PROCESSING', 'DEAD')"
                )
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void additiveClaimIndexesMatchRuntimeOrderingsAndAggregateHeadGuard()
            throws Exception {
        String migration = new ClassPathResource(
                "db/migration/V1_10_195__integration_outbox_claim_indexes.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .containsIgnoringCase(
                        "ON integration_outbox (status, available_at, integration_outbox_id)"
                )
                .containsIgnoringCase(
                        "ON integration_outbox (status, processing_lease_until, integration_outbox_id)"
                )
                .containsIgnoringCase("aggregate_type")
                .containsIgnoringCase("aggregate_id")
                .containsIgnoringCase("status")
                .containsIgnoringCase("integration_outbox_id");
    }

    @Test
    void everyTerminalOrRetryTransitionIsStrictlyTokenFenced() {
        assertStrictFence(IntegrationOutboxRepository.SUCCEEDED_SQL);
        assertStrictFence(IntegrationOutboxRepository.RETRY_SQL);
        assertStrictFence(IntegrationOutboxRepository.DEAD_SQL);

        assertThat(IntegrationOutboxRepository.RETRY_SQL)
                .containsIgnoringCase("attempt_count < max_attempts")
                .contains("processing_token = NULL")
                .contains("completed_at = NULL");
        assertThat(IntegrationOutboxRepository.SUCCEEDED_SQL)
                .contains("completed_at = CURRENT_TIMESTAMP(6)");
        assertThat(IntegrationOutboxRepository.DEAD_SQL)
                .contains("completed_at = CURRENT_TIMESTAMP(6)");
    }

    @Test
    void relayIsOffByDefaultAndSchedulerIsConditional() throws Exception {
        String properties = new ClassPathResource("application.properties")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(properties).contains(
                "otziv.integration.outbox.relay-enabled=${OTZIV_INTEGRATION_OUTBOX_RELAY_ENABLED:false}"
        );

        ConditionalOnProperty condition = IntegrationOutboxScheduler.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("otziv.integration.outbox");
        assertThat(condition.name()).containsExactly("relay-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void statusEndpointIsAdminOwnerOnlyAndContainsNoEventData() {
        RequestMapping mapping = IntegrationOutboxStatusController.class
                .getAnnotation(RequestMapping.class);
        PreAuthorize authorization = IntegrationOutboxStatusController.class
                .getAnnotation(PreAuthorize.class);

        assertThat(mapping.value()).containsExactly("/api/admin/integration-outbox");
        assertThat(authorization.value()).isEqualTo("hasAnyRole('ADMIN', 'OWNER')");
        assertThat(IntegrationOutboxStatusResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain(
                        "payload",
                        "aggregateId",
                        "processingToken",
                        "processingOwner",
                        "lastError"
                );
    }

    private void assertStrictFence(String sql) {
        assertThat(sql)
                .containsIgnoringCase("status = 'PROCESSING'")
                .containsIgnoringCase("processing_token = :processingToken");
    }

    private void assertSafeAggregateHeadClaim(String sql) {
        assertThat(sql)
                .containsIgnoringCase("candidate.event_type IN (:allowedEventTypes)")
                .containsIgnoringCase("NOT EXISTS")
                .containsIgnoringCase(
                        "earlier.aggregate_type = candidate.aggregate_type"
                )
                .containsIgnoringCase("earlier.aggregate_id = candidate.aggregate_id")
                .containsIgnoringCase(
                        "earlier.integration_outbox_id < candidate.integration_outbox_id"
                )
                .containsIgnoringCase(
                        "earlier.status IN ('PENDING', 'PROCESSING', 'DEAD')"
                );
    }
}
