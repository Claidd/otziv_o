package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.workload_shadow.notification.WorkloadShadowClaimedNotification;
import com.hunt.otziv.workload_shadow.notification.WorkloadShadowDeliveryOutcome;
import com.hunt.otziv.workload_shadow.notification.WorkloadShadowNotificationEvent;
import com.hunt.otziv.workload_shadow.repository.projection.WorkloadShadowClaimedNotificationProjection;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowNotificationStoreTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 27, 12, 0);
    private static final LocalDateTime LEASE_UNTIL = NOW.plusMinutes(5);

    @Mock private WorkloadShadowEventRepository eventRepository;
    @Mock private WorkloadShadowRunRepository runRepository;
    @Mock private WorkloadShadowWorkerDailyRepository workerDailyRepository;
    @Mock private WorkloadShadowTransferRepository transferRepository;
    @Mock private WorkloadShadowClaimedNotificationProjection projection;

    private ObjectMapper objectMapper;
    private WorkloadShadowNotificationStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        store = new WorkloadShadowNotificationStore(
                eventRepository,
                runRepository,
                workerDailyRepository,
                transferRepository,
                objectMapper
        );
    }

    @Test
    void loadsClaimedEventsAndManagerBindingsWithOneRepositoryQuery() {
        List<Long> eventIds = List.of(1L, 2L);
        when(projection.getId()).thenReturn(1L);
        when(projection.getSeverity()).thenReturn("WARNING");
        when(projection.getEventType()).thenReturn("STAFFING_REQUIRED");
        when(projection.getManagerId()).thenReturn(7L);
        when(projection.getTitle()).thenReturn("Нужен сотрудник");
        when(projection.getMessage()).thenReturn("Нет получателя");
        when(projection.getTargetGroupType()).thenReturn("MANAGER_AUDIT");
        when(projection.getTargetGroupChatId()).thenReturn(-100L);
        when(projection.getDeliveryAttempts()).thenReturn(2);
        when(projection.getManagerAuditGroupChatId()).thenReturn(-100L);
        when(eventRepository.findClaimedEvents(
                eventIds,
                NOW,
                LEASE_UNTIL
        )).thenReturn(List.of(projection));

        List<WorkloadShadowClaimedNotification> result =
                store.findClaimed(eventIds, NOW, LEASE_UNTIL);

        assertThat(result).containsExactly(new WorkloadShadowClaimedNotification(
                new WorkloadShadowNotificationEvent(
                        1L,
                        "WARNING",
                        "STAFFING_REQUIRED",
                        7L,
                        "Нужен сотрудник",
                        "Нет получателя",
                        "MANAGER_AUDIT",
                        -100L,
                        2
                ),
                -100L
        ));
        verify(eventRepository).findClaimedEvents(eventIds, NOW, LEASE_UNTIL);
    }

    @Test
    void writesAllDeliveryOutcomesAsOneBoundedJsonBatch() throws Exception {
        WorkloadShadowNotificationEvent event = new WorkloadShadowNotificationEvent(
                1L,
                "WARNING",
                "STAFFING_REQUIRED",
                7L,
                "Нужен сотрудник",
                "Нет получателя",
                "MANAGER_AUDIT",
                -100L,
                0
        );
        String longError = "ошибка\n" + "x".repeat(1100);
        List<WorkloadShadowDeliveryOutcome> outcomes = List.of(
                WorkloadShadowDeliveryOutcome.sent(event, NOW),
                WorkloadShadowDeliveryOutcome.retry(
                        new WorkloadShadowNotificationEvent(
                                2L,
                                "WARNING",
                                "STAFFING_REQUIRED",
                                7L,
                                "Нужен сотрудник",
                                "Нет получателя",
                                "MANAGER_AUDIT",
                                -100L,
                                3
                        ),
                        LEASE_UNTIL,
                        "TELEGRAM_SEND_FAILED",
                        longError
                )
        );
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        when(eventRepository.applyDeliveryOutcomes(
                json.capture(),
                eq(NOW),
                eq(LEASE_UNTIL)
        )).thenReturn(2);

        int updated = store.applyDeliveryOutcomes(outcomes, NOW, LEASE_UNTIL);

        JsonNode payload = objectMapper.readTree(json.getValue());
        assertThat(updated).isEqualTo(2);
        assertThat(payload).hasSize(2);
        assertThat(payload.get(0).get("deliveryStatus").asText()).isEqualTo("SENT");
        assertThat(payload.get(0).get("deliveryAttempts").asInt()).isZero();
        assertThat(payload.get(0).get("deliveredAt").asText())
                .isEqualTo("2026-07-27 12:00:00.000000");
        assertThat(payload.get(1).get("deliveryStatus").asText()).isEqualTo("RETRY");
        assertThat(payload.get(1).get("deliveryAttempts").asInt()).isEqualTo(4);
        assertThat(payload.get(1).get("nextAttemptAt").asText())
                .isEqualTo("2026-07-27 12:05:00.000000");
        assertThat(payload.get(1).get("error").asText())
                .doesNotContain("\n")
                .hasSize(1000);
        verify(eventRepository).applyDeliveryOutcomes(
                json.getValue(),
                NOW,
                LEASE_UNTIL
        );
    }
}
