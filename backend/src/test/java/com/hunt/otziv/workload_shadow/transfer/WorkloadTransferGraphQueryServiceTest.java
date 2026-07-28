package com.hunt.otziv.workload_shadow.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.SourceCompanyProjection;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowSettingsService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkloadTransferGraphQueryServiceTest {

    @Test
    void loadsAllSourcesWithOneCallPerBulkDataset() {
        WorkloadTransferGraphRepository repository =
                mock(WorkloadTransferGraphRepository.class);
        AppSettingService appSettingService = mock(AppSettingService.class);
        WorkloadShadowSettingsService settingsService =
                mock(WorkloadShadowSettingsService.class);
        WorkloadShadowSettingsResponse settings = mock(WorkloadShadowSettingsResponse.class);
        when(settingsService.current()).thenReturn(settings);
        when(settings.newMinutesPerCard()).thenReturn(5);
        when(settings.correctionMinutesPerOrder()).thenReturn(10);
        when(settings.walkMinutesPerCard()).thenReturn(4);
        when(settings.publishMinutesPerCard()).thenReturn(3);
        when(settings.recoveryMinutesPerTask()).thenReturn(10);
        when(settings.badMinutesPerTask()).thenReturn(10);
        when(appSettingService.getInt(anyString(), anyInt())).thenReturn(14);
        SourceCompanyProjection firstCompany = sourceCompany(1L, 11L, 101L);
        SourceCompanyProjection secondCompany = sourceCompany(2L, 12L, 102L);
        when(repository.findSourceCompanies(List.of(1L, 2L))).thenReturn(List.of(
                firstCompany,
                secondCompany
        ));
        when(repository.findCompanyWorkerLinks(anyCollection())).thenReturn(List.of());
        when(repository.findCompanyOrderOwnership(anyCollection())).thenReturn(List.of());
        when(repository.findActiveOrders(anyCollection(), anyCollection())).thenReturn(List.of());
        when(repository.findUnpublishedReviews(anyCollection(), anyCollection())).thenReturn(List.of());
        when(repository.findOpenRecoveryTasks(anyCollection(), anyCollection())).thenReturn(List.of());
        when(repository.findOpenBadTasks(anyCollection(), anyCollection())).thenReturn(List.of());

        WorkloadTransferGraphQueryService service = new WorkloadTransferGraphQueryService(
                repository,
                appSettingService,
                settingsService
        );
        Map<Long, List<WorkloadTransferCompanyGraph>> result = service.findActiveGraphs(
                List.of(1L, 2L),
                LocalDate.of(2026, 7, 27)
        );

        assertEquals(Set.of(1L, 2L), result.keySet());
        assertEquals(1, result.get(1L).size());
        assertEquals(1, result.get(2L).size());
        verify(repository, times(1)).findSourceCompanies(List.of(1L, 2L));
        verify(repository, times(1)).findCompanyWorkerLinks(anyCollection());
        verify(repository, times(1)).findCompanyOrderOwnership(anyCollection());
        verify(repository, times(1)).findActiveOrders(anyCollection(), anyCollection());
        verify(repository, times(1)).findUnpublishedReviews(anyCollection(), anyCollection());
        verify(repository, times(1)).findOpenRecoveryTasks(anyCollection(), anyCollection());
        verify(repository, times(1)).findOpenBadTasks(anyCollection(), anyCollection());
        verify(repository, never()).findOrderDetails(anyCollection());
        verify(repository, never()).findActivePerformerCounts(anyCollection());
        verify(repository, never()).findExternalCheckCounts(anyCollection());
    }

    private SourceCompanyProjection sourceCompany(
            long sourceWorkerId,
            long managerId,
            long companyId
    ) {
        SourceCompanyProjection value = mock(SourceCompanyProjection.class);
        when(value.getSourceWorkerId()).thenReturn(sourceWorkerId);
        when(value.getManagerId()).thenReturn(managerId);
        when(value.getCompanyId()).thenReturn(companyId);
        when(value.getCompanyTitle()).thenReturn("Компания " + companyId);
        when(value.getCompanyActive()).thenReturn(true);
        when(value.getCompanyStatus()).thenReturn("В работе");
        when(value.getCompanyManagerId()).thenReturn(managerId);
        return value;
    }
}
