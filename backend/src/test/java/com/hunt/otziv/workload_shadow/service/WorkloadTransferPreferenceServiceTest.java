package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferPreferenceRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferPreferenceServiceTest {

    @Mock private WorkloadTransferPreferenceRepository preferenceRepository;
    @Mock private WorkloadTransferPreferenceRepository.PreferenceProjection projection;
    @Mock private BusinessAuditService businessAuditService;
    @Mock private WorkloadShadowRefreshSignal refreshSignal;

    private WorkloadTransferPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadTransferPreferenceService(
                preferenceRepository,
                businessAuditService,
                refreshSignal
        );
    }

    @Test
    void unchangedPreferenceNeedsOnlyOneReadQuery() {
        preference(true);

        var response = service.update(" worker ", true);

        assertThat(response.workerId()).isEqualTo(17L);
        assertThat(response.acceptsCompanyTransfers()).isTrue();
        verify(preferenceRepository).findByUsername("worker");
        verify(preferenceRepository, never()).updatePreference(
                eq(17L),
                eq("worker"),
                eq(true),
                any(LocalDateTime.class)
        );
        verify(refreshSignal, never()).markDirty();
    }

    @Test
    void changedPreferenceUsesOneReadAndOneGuardedUpdate() {
        preference(true);
        when(preferenceRepository.updatePreference(
                eq(17L),
                eq("worker"),
                eq(false),
                any(LocalDateTime.class)
        )).thenReturn(1);
        ArgumentCaptor<LocalDateTime> changedAt =
                ArgumentCaptor.forClass(LocalDateTime.class);

        var response = service.update("worker", false);

        verify(preferenceRepository).findByUsername("worker");
        verify(preferenceRepository).updatePreference(
                eq(17L),
                eq("worker"),
                eq(false),
                changedAt.capture()
        );
        assertThat(response.acceptsCompanyTransfers()).isFalse();
        assertThat(response.changedAt()).isEqualTo(changedAt.getValue());
        verify(businessAuditService).recordSafely(
                eq("UPDATE_WORKLOAD_TRANSFER_PREFERENCE"),
                eq("WORKER"),
                eq(17L),
                isNull(),
                isNull(),
                any(),
                eq(response),
                eq("Специалист исключил себя из списка получения новых компаний")
        );
        verify(refreshSignal).markDirty();
    }

    private void preference(boolean accepts) {
        when(projection.getWorkerId()).thenReturn(17L);
        when(projection.getAcceptsCompanyTransfers()).thenReturn(accepts);
        when(projection.getChangedAt()).thenReturn(
                LocalDateTime.of(2026, 7, 20, 10, 0)
        );
        when(preferenceRepository.findByUsername("worker"))
                .thenReturn(Optional.of(projection));
    }
}
