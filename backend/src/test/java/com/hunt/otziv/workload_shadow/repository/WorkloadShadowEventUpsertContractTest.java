package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowEventUpsertContractTest {

    @Test
    void everyEventUpsertPreservesDeliveryStateWithinTheSameActiveEpisode() throws Exception {
        for (QueryContract contract : eventUpserts()) {
            String sql = normalized(contract.sql());

            assertThat(sql)
                    .as(contract.name())
                    .contains(
                            "delivery_attempts = case",
                            "when workload_shadow_events.active = false then 0",
                            "when workload_shadow_events.delivery_status in "
                                    + "('processing', 'retry', 'pending') "
                                    + "then workload_shadow_events.delivery_attempts",
                            "when workload_shadow_events.delivery_status = 'dead' "
                                    + "and coalesce(workload_shadow_events.last_error_code, '') "
                                    + "<> 'missing_group_binding' "
                                    + "then workload_shadow_events.delivery_attempts",
                            "when workload_shadow_events.delivery_status = 'retry' "
                                    + "then workload_shadow_events.next_attempt_at",
                            "when workload_shadow_events.delivery_status = 'pending' then case",
                            "least( workload_shadow_events.next_attempt_at, values(next_attempt_at) )",
                            "when workload_shadow_events.delivery_status = 'retry' then 'retry'",
                            "when workload_shadow_events.delivery_status = 'pending' then 'pending'"
                    );
        }
    }

    @Test
    void everyEventUpsertResetsAttemptsOnlyForANewOrRepairedDeliveryEpisode() throws Exception {
        for (QueryContract contract : eventUpserts()) {
            String sql = normalized(contract.sql());

            assertThat(sql)
                    .as(contract.name())
                    .contains(
                            "when values(target_group_chat_id) is null "
                                    + "or values(target_group_chat_id) >= 0 then 0",
                            "when workload_shadow_events.active = false then 0",
                            "when workload_shadow_events.active = false "
                                    + "then values(delivery_status)",
                            "when workload_shadow_events.delivery_status = 'sent' "
                                    + "and workload_shadow_events.delivered_at >= :cooldownstart "
                                    + "then workload_shadow_events.delivery_attempts",
                            "when workload_shadow_events.delivery_status = 'sent' "
                                    + "and workload_shadow_events.delivered_at >= :cooldownstart "
                                    + "then 'sent'",
                            "else values(delivery_status)"
                    );

            int attemptsAssignment = sql.indexOf("delivery_attempts = case");
            int nextAttemptAssignment = sql.indexOf("next_attempt_at = case");
            int statusAssignment = sql.indexOf("delivery_status = case");
            assertThat(attemptsAssignment)
                    .as(contract.name() + ": attempts должны вычисляться по старому status")
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(statusAssignment);
            assertThat(nextAttemptAssignment)
                    .as(contract.name() + ": next_attempt_at должен вычисляться по старому status")
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(statusAssignment);
        }
    }

    @Test
    void everyEventUpsertKeepsSkippedEventsOutOfTheDeliveryQueue() throws Exception {
        for (QueryContract contract : eventUpserts()) {
            String sql = normalized(contract.sql());

            assertThat(sql)
                    .as(contract.name())
                    .contains(
                            "when values(delivery_status) = 'skipped' then 0",
                            "when values(delivery_status) = 'skipped' then null",
                            "when workload_shadow_events.delivery_status = 'skipped' then null",
                            "when values(delivery_status) = 'skipped' then 'skipped'",
                            "when workload_shadow_events.delivery_status = 'skipped' "
                                    + "then 'skipped'",
                            "processing_started_at = case",
                            "processing_lease_until = case",
                            "or workload_shadow_events.delivery_status = 'skipped' then null"
                    );
        }
    }

    @Test
    void missedFinalSnapshotUsesNotificationToggleBeforeCreatingADueEvent() throws Exception {
        String sql = normalized(query(
                WorkloadShadowProjectionRepository.class,
                "emitMissedFinalSnapshotEvents",
                LocalDate.class,
                LocalDateTime.class,
                LocalDateTime.class,
                boolean.class,
                Long.class
        ).sql());

        assertThat(sql).contains(
                "when :groupnotificationsenabled = false then 'skipped'",
                "when :groupnotificationsenabled = true "
                        + "and :notificationgroupchatid < 0 then :now"
        );
    }

    @Test
    void notificationBaselineSkipsEveryActiveAdminOwnerEventAndClearsItsLease()
            throws Exception {
        String sql = normalized(query(
                WorkloadShadowEventRepository.class,
                "baselineActiveAdminOwnerEvents"
        ).sql());

        assertThat(sql).contains(
                "update workload_shadow_events set delivery_status = 'skipped'",
                "delivery_attempts = 0",
                "next_attempt_at = null",
                "processing_started_at = null",
                "processing_lease_until = null",
                "last_error_code = 'notification_baseline'",
                "where active = 1",
                "target_group_type = 'admin_owner_monitoring'"
        );
    }

    private List<QueryContract> eventUpserts() throws Exception {
        return List.of(
                query(
                        WorkloadShadowProjectionRepository.class,
                        "emitMissedFinalSnapshotEvents",
                        LocalDate.class,
                        LocalDateTime.class,
                        LocalDateTime.class,
                        boolean.class,
                        Long.class
                ),
                query(
                        WorkloadShadowProjectionRepository.class,
                        "upsertEvents",
                        String.class,
                        LocalDateTime.class
                ),
                query(
                        WorkloadShadowTransferRepository.class,
                        "upsertEvents",
                        String.class,
                        LocalDateTime.class,
                        LocalDateTime.class
                )
        );
    }

    private QueryContract query(
            Class<?> repositoryType,
            String methodName,
            Class<?>... parameterTypes
    ) throws Exception {
        Method method = repositoryType.getDeclaredMethod(methodName, parameterTypes);
        Query query = method.getAnnotation(Query.class);
        assertThat(query)
                .as(repositoryType.getSimpleName() + "." + methodName + " должен иметь @Query")
                .isNotNull();
        return new QueryContract(
                repositoryType.getSimpleName() + "." + methodName,
                query.value()
        );
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private record QueryContract(String name, String sql) {
    }
}
