package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository.RecommendationCandidateProjection;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WorkloadTotals;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferWorkflowServiceTest {

    @Mock private WorkloadTransferWorkflowRepository repository;
    @Mock private WorkloadTransferGraphQueryService graphQueryService;
    @Mock private WorkloadLiveSettingsService liveSettingsService;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;

    private ObjectMapper objectMapper;
    private WorkloadTransferWorkflowService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WorkloadTransferWorkflowService(
                repository,
                graphQueryService,
                liveSettingsService,
                shadowSettingsService,
                new WorkloadTransferGraphSnapshotService(objectMapper)
        );
        when(shadowSettingsService.current()).thenReturn(null);
        when(shadowSettingsService.zone(null))
                .thenReturn(ZoneId.of("Asia/Irkutsk"));
    }

    @Test
    void stagesAllSelectedWorkflowsAndCandidatesWithTwoBulkWrites()
            throws Exception {
        WorkloadLiveSettingsResponse settings = settings();
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(liveSettingsService.managerAllowed(eq(settings), anyLong()))
                .thenReturn(true);
        List<RecommendationCandidateProjection> candidates = List.of(
                candidate(101L, 11L, 1001L, 201L, 1),
                candidate(101L, 11L, 1001L, 202L, 2),
                candidate(102L, 12L, 1002L, 203L, 1),
                candidate(102L, 12L, 1002L, 204L, 2)
        );
        when(repository.findRecommendationCandidates()).thenReturn(candidates);
        when(repository.reservedByManagerSince(any())).thenReturn(List.of());
        when(graphQueryService.findActiveGraphs(any(), any())).thenReturn(Map.of(
                101L, List.of(graph(1001L, 11L, 101L)),
                102L, List.of(graph(1002L, 12L, 102L))
        ));
        when(repository.countAppliedExecutions()).thenReturn(0L);
        when(repository.insertWorkflowsBulk(
                anyString(),
                eq("CANARY"),
                eq(true),
                any(LocalDate.class),
                any(LocalDateTime.class)
        )).thenReturn(2);
        when(repository.insertWorkflowCandidatesBulk(
                anyString(),
                any(LocalDateTime.class)
        )).thenReturn(4);
        when(repository.countIncompleteWorkflowQueues(anyString()))
                .thenReturn(0L);

        var result = service.stageEligibleRecommendations();

        assertThat(result.staged()).isEqualTo(2);
        ArgumentCaptor<String> workflowsJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> candidatesJson = ArgumentCaptor.forClass(String.class);
        verify(repository, times(1)).insertWorkflowsBulk(
                workflowsJson.capture(),
                eq("CANARY"),
                eq(true),
                any(LocalDate.class),
                any(LocalDateTime.class)
        );
        verify(repository, times(1)).insertWorkflowCandidatesBulk(
                candidatesJson.capture(),
                any(LocalDateTime.class)
        );
        verify(repository, times(1))
                .countIncompleteWorkflowQueues(workflowsJson.getValue());

        JsonNode workflowRows = objectMapper.readTree(workflowsJson.getValue());
        assertThat(workflowRows.size()).isEqualTo(2);
        assertThat(workflowRows.get(0).get("managerId").asLong()).isEqualTo(11L);
        assertThat(workflowRows.get(1).get("managerId").asLong()).isEqualTo(12L);
        assertThat(workflowRows.get(0).get("candidateCount").asInt()).isEqualTo(2);
        assertThat(workflowRows.get(1).get("candidateCount").asInt()).isEqualTo(2);

        JsonNode candidateRows = objectMapper.readTree(candidatesJson.getValue());
        assertThat(candidateRows.size()).isEqualTo(4);
        assertThat(List.of(
                candidateRows.get(0).get("managerId").asLong(),
                candidateRows.get(1).get("managerId").asLong(),
                candidateRows.get(2).get("managerId").asLong(),
                candidateRows.get(3).get("managerId").asLong()
        )).containsExactly(11L, 11L, 12L, 12L);
        assertThat(List.of(
                candidateRows.get(0).get("sourceWorkerId").asLong(),
                candidateRows.get(1).get("sourceWorkerId").asLong(),
                candidateRows.get(2).get("sourceWorkerId").asLong(),
                candidateRows.get(3).get("sourceWorkerId").asLong()
        )).containsExactly(101L, 101L, 102L, 102L);
        assertThat(List.of(
                candidateRows.get(0).get("companyId").asLong(),
                candidateRows.get(1).get("companyId").asLong(),
                candidateRows.get(2).get("companyId").asLong(),
                candidateRows.get(3).get("companyId").asLong()
        )).containsExactly(1001L, 1001L, 1002L, 1002L);
    }

    private RecommendationCandidateProjection candidate(
            long sourceWorkerId,
            long managerId,
            long companyId,
            long candidateWorkerId,
            int sequenceNumber
    ) {
        RecommendationCandidateProjection row =
                org.mockito.Mockito.mock(RecommendationCandidateProjection.class);
        when(row.getShadowCaseId()).thenReturn(companyId + 10_000L);
        when(row.getManagerId()).thenReturn(managerId);
        when(row.getSourceWorkerId()).thenReturn(sourceWorkerId);
        when(row.getCompanyId()).thenReturn(companyId);
        if (sequenceNumber == 1) {
            when(row.getFinanciallyUnsafeOrderCount()).thenReturn(0L);
        }
        when(row.getCandidateWorkerId()).thenReturn(candidateWorkerId);
        when(row.getSequenceNumber()).thenReturn(sequenceNumber);
        when(row.getRating()).thenReturn(new BigDecimal("98.50"));
        when(row.getHundredPercentDays()).thenReturn(14);
        when(row.getFailureDays()).thenReturn(0);
        when(row.getCurrentEstimatedMinutes()).thenReturn(5L);
        when(row.getTargetGroupChatId()).thenReturn(-8_000_000_000L - candidateWorkerId);
        when(row.getCandidateTelegramId()).thenReturn(8_000_000_000L + candidateWorkerId);
        return row;
    }

    private WorkloadTransferCompanyGraph graph(
            long companyId,
            long managerId,
            long sourceWorkerId
    ) {
        WorkloadTotals totals = new WorkloadTotals(
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0
        );
        return new WorkloadTransferCompanyGraph(
                companyId,
                "Компания " + companyId,
                true,
                "В работе",
                managerId,
                true,
                List.of(sourceWorkerId),
                false,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                totals,
                List.of()
        );
    }

    private WorkloadLiveSettingsResponse settings() {
        return new WorkloadLiveSettingsResponse(
                "CANARY",
                true,
                "2026-07-01",
                14,
                168,
                2,
                List.of(11L, 12L),
                15,
                "00:00",
                "23:59:59",
                10,
                10,
                30,
                5,
                false,
                1L
        );
    }
}
