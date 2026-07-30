package com.hunt.otziv.workload_shadow.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowEventRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferExecutionFailureServiceTest {

    @Mock private WorkloadTransferExecutionRepository executionRepository;
    @Mock private WorkloadShadowEventRepository eventRepository;
    @Mock private WorkloadShadowSettingsService settingsService;
    @Mock private WorkloadShadowSettingsResponse settings;

    private WorkloadTransferExecutionFailureService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadTransferExecutionFailureService(
                executionRepository,
                eventRepository,
                settingsService
        );
        when(settingsService.current()).thenReturn(settings);
        when(settingsService.zone(settings))
                .thenReturn(ZoneId.of("Asia/Irkutsk"));
        when(settings.groupNotificationsEnabled()).thenReturn(true);
        when(settings.notificationGroupChatId()).thenReturn(-5_181_415_104L);
        when(settings.alertCooldownMinutes()).thenReturn(60);
    }

    @Test
    void blockingExecutionAlsoQueuesDeduplicatedAdminOwnerAlert() {
        when(executionRepository.closeAcceptedCandidateForBlockedWorkflow(
                eq(42L),
                eq("Связи изменились"),
                any()
        )).thenReturn(1);
        when(executionRepository.closeAcceptedOfferForBlockedWorkflow(
                eq(42L),
                eq("GRAPH_CHANGED"),
                eq("Связи изменились"),
                any()
        )).thenReturn(1);
        when(executionRepository.blockWorkflow(
                eq(42L),
                eq("BLOCKED_EXECUTION"),
                eq("GRAPH_CHANGED"),
                eq("Связи изменились"),
                any()
        )).thenReturn(1);

        service.block(42L, " GRAPH_CHANGED ", " Связи изменились ");

        InOrder terminalOrder = inOrder(executionRepository);
        terminalOrder.verify(executionRepository)
                .closeAcceptedCandidateForBlockedWorkflow(
                        eq(42L),
                        eq("Связи изменились"),
                        any()
                );
        terminalOrder.verify(executionRepository)
                .closeAcceptedOfferForBlockedWorkflow(
                        eq(42L),
                        eq("GRAPH_CHANGED"),
                        eq("Связи изменились"),
                        any()
                );
        terminalOrder.verify(executionRepository).blockWorkflow(
                eq(42L),
                eq("BLOCKED_EXECUTION"),
                eq("GRAPH_CHANGED"),
                eq("Связи изменились"),
                any()
        );
        verify(eventRepository).upsertLiveExecutionFailure(
                eq(42L),
                eq("GRAPH_CHANGED"),
                eq("Связи изменились"),
                eq(true),
                eq(-5_181_415_104L),
                any(),
                any()
        );
    }
}
