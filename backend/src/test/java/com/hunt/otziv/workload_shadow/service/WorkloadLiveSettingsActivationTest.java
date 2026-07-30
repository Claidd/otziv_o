package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveActivationRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveSettingsRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkloadLiveSettingsActivationTest {

    @Mock private WorkloadLiveSettingsRepository repository;
    @Mock private AppSettingService appSettingService;
    @Mock private BusinessAuditService businessAuditService;
    @Mock private WorkloadLiveActivationGate activationGate;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;
    @Mock private WorkloadTransferOfferRepository offerRepository;
    @Mock private WorkloadTransferWorkflowRepository workflowRepository;

    private WorkloadLiveSettingsService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadLiveSettingsService(
                repository,
                new ObjectMapper(),
                appSettingService,
                businessAuditService,
                activationGate,
                shadowSettingsService,
                offerRepository,
                workflowRepository
        );
    }

    @Test
    void exactConfirmationIsRequiredBeforeReadingOrChangingSettings() {
        assertThatThrownBy(() -> service.activate(
                new WorkloadLiveActivationRequest("CANARY", "включить", 1L)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(WorkloadLiveSettingsService.ACTIVATION_CONFIRMATION);

        verifyNoInteractions(
                repository,
                activationGate,
                appSettingService,
                businessAuditService
        );
    }

    @Test
    void activationRunsReadinessBeforeOneRevisionGuardedAtomicUpdate() {
        WorkloadLiveSettingsResponse before = settings("SHADOW", false, 4L);
        WorkloadLiveSettingsResponse after = settings("CANARY", true, 5L);
        WorkloadLiveSettingsService spy = spy(service);
        doReturn(before, after).when(spy).current();
        when(repository.updateAllWithRevision(
                anyString(),
                eq(WorkloadLiveSettingsService.PREFIX),
                eq(WorkloadLiveSettingsService.REVISION_KEY),
                eq(4L)
        )).thenReturn(17);

        WorkloadLiveSettingsResponse result = spy.activate(
                new WorkloadLiveActivationRequest(
                        " canary ",
                        WorkloadLiveSettingsService.ACTIVATION_CONFIRMATION,
                        4L
                )
        );

        assertThat(result).isSameAs(after);
        verify(activationGate).assertReady("CANARY", before);
        ArgumentCaptor<String> writes = ArgumentCaptor.forClass(String.class);
        verify(repository).updateAllWithRevision(
                writes.capture(),
                eq(WorkloadLiveSettingsService.PREFIX),
                eq(WorkloadLiveSettingsService.REVISION_KEY),
                eq(4L)
        );
        assertThat(writes.getValue())
                .contains("workload.live.mode", "CANARY")
                .contains("workload.live.apply-enabled", "true")
                .contains("workload.live.retention-days", "400")
                .contains("workload.live.settings-revision", "5");
        verify(appSettingService).invalidateByPrefix(
                WorkloadLiveSettingsService.PREFIX
        );
    }

    private WorkloadLiveSettingsResponse settings(
            String mode,
            boolean apply,
            long revision
    ) {
        return new WorkloadLiveSettingsResponse(
                mode,
                apply,
                "2026-07-01",
                14,
                168,
                2,
                List.of(7L),
                15,
                "10:00",
                "21:00",
                1,
                3,
                30,
                5,
                true,
                revision
        );
    }
}
