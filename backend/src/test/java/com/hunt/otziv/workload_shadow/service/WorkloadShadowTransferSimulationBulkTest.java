package com.hunt.otziv.workload_shadow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowTransferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowTransferRepository.RecipientProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowTransferRepository.SourceWorkerProjection;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewStage;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.Warning;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningSeverity;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WorkloadTotals;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class WorkloadShadowTransferSimulationBulkTest {

    @Test
    void requestsGraphsOnceForAllSourceWorkers() {
        WorkloadShadowTransferRepository repository =
                mock(WorkloadShadowTransferRepository.class);
        WorkloadShadowSettingsService settingsService =
                mock(WorkloadShadowSettingsService.class);
        WorkloadTransferGraphQueryService graphQueryService =
                mock(WorkloadTransferGraphQueryService.class);
        WorkloadShadowSettingsResponse settings = mock(WorkloadShadowSettingsResponse.class);
        when(settingsService.current()).thenReturn(settings);
        when(settings.allowedFailureDays()).thenReturn(3);
        SourceWorkerProjection firstSource = source(1L, 11L);
        SourceWorkerProjection secondSource = source(2L, 12L);
        when(repository.findSourceWorkers(3)).thenReturn(List.of(
                firstSource,
                secondSource
        ));
        when(repository.findRecipients()).thenReturn(List.of());
        when(graphQueryService.findActiveGraphs(anyList(), any(LocalDate.class)))
                .thenReturn(Map.of());

        WorkloadShadowTransferSimulationService service =
                new WorkloadShadowTransferSimulationService(
                        repository,
                        settingsService,
                        graphQueryService,
                        new ObjectMapper()
                );
        LocalDateTime observedAt = LocalDateTime.of(2026, 7, 27, 22, 30);
        WorkloadShadowTransferSimulationService.SimulationResult result =
                service.rebuild(77L, observedAt);

        assertEquals(0, result.transferCaseCount());
        assertEquals(0, result.eventCount());
        verify(graphQueryService, times(1)).findActiveGraphs(
                List.of(1L, 2L),
                observedAt.toLocalDate()
        );
        verify(repository, times(1)).deactivateTransferCases(observedAt);
        verify(repository, times(1)).deleteInactiveCandidates();
        verify(repository, times(1)).deactivateUnseenEvents(observedAt);
        verify(repository, never()).upsertTransferCases(
                anyString(),
                anyLong(),
                any(LocalDateTime.class)
        );
    }

    @Test
    void keepsBrokenGraphVisibleButDoesNotCreateTransferOrStaffingRecommendation()
            throws Exception {
        WorkloadShadowTransferRepository repository =
                mock(WorkloadShadowTransferRepository.class);
        WorkloadShadowSettingsService settingsService =
                mock(WorkloadShadowSettingsService.class);
        WorkloadTransferGraphQueryService graphQueryService =
                mock(WorkloadTransferGraphQueryService.class);
        WorkloadShadowSettingsResponse settings = mock(WorkloadShadowSettingsResponse.class);
        when(settingsService.current()).thenReturn(settings);
        when(settings.allowedFailureDays()).thenReturn(3);
        when(settings.fourthFailurePercent()).thenReturn(15);
        when(settings.fourthFailureMaxCompanies()).thenReturn(1);
        SourceWorkerProjection source = source(1L, 11L);
        when(repository.findSourceWorkers(3)).thenReturn(List.of(source));
        when(repository.findRecipients()).thenReturn(List.of());
        when(graphQueryService.findActiveGraphs(anyList(), any(LocalDate.class)))
                .thenReturn(Map.of(1L, List.of(brokenGraph())));

        ObjectMapper objectMapper = new ObjectMapper();
        WorkloadShadowTransferSimulationService service =
                new WorkloadShadowTransferSimulationService(
                        repository,
                        settingsService,
                        graphQueryService,
                        objectMapper
                );
        service.rebuild(77L, LocalDateTime.of(2026, 7, 27, 22, 30));

        ArgumentCaptor<String> casesJson = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertTransferCases(casesJson.capture(), anyLong(), any());
        JsonNode transferCase = objectMapper.readTree(casesJson.getValue()).get(0);
        assertEquals("BLOCKED_GRAPH", transferCase.get("caseStatus").asText());
        assertEquals(0, transferCase.get("candidateCount").asInt());
        assertFalse(transferCase.get("staffingRequired").asBoolean());
        verify(repository, never()).upsertCandidates(anyString());

        ArgumentCaptor<String> eventsJson = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertEvents(eventsJson.capture(), any(), any());
        JsonNode events = objectMapper.readTree(eventsJson.getValue());
        assertEquals(1, events.size());
        assertEquals(
                "TRANSFER_GRAPH_WARNING",
                events.get(0).get("eventType").asText()
        );
        assertTrue(events.get(0).get("message").asText().contains("Специалист: Елена Ч."));
        assertTrue(events.get(0).get("message").asText().contains("Передача не выполнялась"));
    }

    @Test
    void informationalBotOwnerMismatchDoesNotBlockRecommendation() throws Exception {
        Warning informationalMismatch = new Warning(
                WarningCode.REVIEW_BOT_OWNER_MISMATCH,
                WarningSeverity.INFO,
                "Аккаунт закреплён за другим специалистом общего городского пула"
        );

        SimulationPayload result = simulateTransferGraphs(List.of(
                transferGraph(201L, false, 0, List.of(informationalMismatch))
        ));

        assertEquals(1, result.cases().size());
        assertEquals("SHADOW_PENDING", result.cases().get(0).get("caseStatus").asText());
        assertEquals(0, result.cases().get(0).get("graphErrorCount").asInt());
        assertFalse(hasEvent(result.events(), "TRANSFER_GRAPH_WARNING"));
    }

    @Test
    void historicalSharedOwnershipWithoutOtherActiveOrdersDoesNotBlockRecommendation()
            throws Exception {
        Warning historicalLink = new Warning(
                WarningCode.SHARED_COMPANY_OWNERSHIP,
                WarningSeverity.INFO,
                "Дополнительная историческая связь без чужих активных заказов"
        );

        SimulationPayload result = simulateTransferGraphs(List.of(
                transferGraph(202L, true, 0, List.of(historicalLink))
        ));

        assertEquals(1, result.cases().size());
        assertEquals("SHADOW_PENDING", result.cases().get(0).get("caseStatus").asText());
        assertEquals(0, result.cases().get(0).get("graphWarningCount").asInt());
        assertEquals(0, result.cases().get(0).get("graphErrorCount").asInt());
        assertFalse(hasEvent(result.events(), "TRANSFER_GRAPH_WARNING"));
    }

    @Test
    void otherWorkerActiveOrderDoesNotBlockSourceOrderBundle() throws Exception {
        Warning activeOwnershipConflict = new Warning(
                WarningCode.OTHER_WORKER_ACTIVE_ORDERS,
                WarningSeverity.INFO,
                "Чужой активный заказ не входит в пакет исходного специалиста"
        );

        SimulationPayload result = simulateTransferGraphs(List.of(
                transferGraph(203L, true, 1, List.of(activeOwnershipConflict))
        ));

        assertEquals(1, result.cases().size());
        assertEquals("SHADOW_PENDING", result.cases().get(0).get("caseStatus").asText());
        assertFalse(hasEvent(result.events(), "TRANSFER_GRAPH_WARNING"));
        assertTrue(hasEvent(result.events(), "STAFFING_REQUIRED"));
    }

    @Test
    void blockedGraphDiagnosticsRespectTierCompanyLimit() throws Exception {
        SimulationPayload result = simulateTransferGraphs(List.of(
                brokenGraph(301L),
                brokenGraph(302L),
                brokenGraph(303L),
                brokenGraph(304L),
                brokenGraph(305L)
        ));

        assertEquals(1, result.cases().size());
        assertEquals("BLOCKED_GRAPH", result.cases().get(0).get("caseStatus").asText());
        assertEquals(1, result.cases().get(0).get("selectionRank").asInt());
        assertEquals(
                1,
                countEvents(result.events(), "TRANSFER_GRAPH_WARNING")
        );
    }

    @Test
    void recommendationsAndBlockedDiagnosticsUseIndependentRanks() throws Exception {
        SimulationPayload result = simulateTransferGraphs(List.of(
                transferGraph(401L, false, 0, List.of()),
                brokenGraph(402L)
        ));

        assertEquals(2, result.cases().size());
        JsonNode recommendation = caseWithStatus(result.cases(), "SHADOW_PENDING");
        JsonNode diagnostic = caseWithStatus(result.cases(), "BLOCKED_GRAPH");
        assertEquals(1, recommendation.get("selectionRank").asInt());
        assertEquals(1, diagnostic.get("selectionRank").asInt());
    }

    @Test
    void createsEmergencyFallbackOnlyForConcreteCardAndEligibleRecipient()
            throws Exception {
        ScenarioResult withoutCard = simulateEmergency(false, true);
        assertTrue(withoutCard.transferCase().get("fallbackWorkerId").isNull());
        assertTrue(withoutCard.transferCase().get("fallbackReviewId").isNull());
        assertFalse(hasEvent(withoutCard.events(), "EMERGENCY_FALLBACK"));

        ScenarioResult ineligibleRecipient = simulateEmergency(true, false);
        assertTrue(ineligibleRecipient.transferCase().get("fallbackWorkerId").isNull());
        assertEquals(500L, ineligibleRecipient.transferCase().get("fallbackReviewId").asLong());
        assertFalse(hasEvent(ineligibleRecipient.events(), "EMERGENCY_FALLBACK"));

        ScenarioResult eligibleRecipient = simulateEmergency(true, true);
        assertEquals(2L, eligibleRecipient.transferCase().get("fallbackWorkerId").asLong());
        assertEquals(500L, eligibleRecipient.transferCase().get("fallbackReviewId").asLong());
        assertTrue(hasEvent(eligibleRecipient.events(), "EMERGENCY_FALLBACK"));
        assertTrue(eventMessage(
                eligibleRecipient.events(),
                "EMERGENCY_FALLBACK"
        ).contains("Резервный специалист: Альфия Л."));
        assertFalse(eventMessage(
                eligibleRecipient.events(),
                "EMERGENCY_FALLBACK"
        ).contains("специалист #2"));
    }

    @Test
    void rotatesFirstOfferCandidateAcrossSelectedRecommendations()
            throws Exception {
        RecipientProjection first = recipient(
                true,
                11L,
                2L,
                "Альфия Л.",
                BigDecimal.valueOf(90)
        );
        RecipientProjection second = recipient(
                true,
                11L,
                3L,
                "Катя К.",
                BigDecimal.valueOf(86)
        );

        SimulationPayload result = simulateTransferGraphs(
                List.of(
                        transferGraph(701L, false, 0, List.of()),
                        transferGraph(702L, false, 0, List.of())
                ),
                List.of(first, second),
                100,
                2
        );

        assertEquals(4, result.candidates().size());
        assertEquals(3L, firstCandidateWorker(result.candidates(), "1:701"));
        assertEquals(2L, firstCandidateWorker(result.candidates(), "1:702"));
    }

    @Test
    void recommendationUsesWorkerNamesAndParagraphsInsteadOfTechnicalIds()
            throws Exception {
        RecipientProjection candidate = recipient(true, 11L);

        SimulationPayload result = simulateTransferGraphs(
                List.of(transferGraph(601L, false, 0, List.of())),
                List.of(candidate)
        );

        String message = eventMessage(result.events(), "TRANSFER_RECOMMENDATION");
        assertTrue(message.contains("Специалист: Елена Ч."));
        assertTrue(message.contains("• Альфия Л. — рейтинг 90"));
        assertTrue(message.contains("Результат: 4 дня ниже 100%"));
        assertTrue(message.contains("\n\nОчередь кандидатов:\n"));
        assertFalse(message.contains("специалиста #"));
        assertFalse(message.contains("#2 ("));
    }

    private SourceWorkerProjection source(long workerId, long managerId) {
        SourceWorkerProjection value = mock(SourceWorkerProjection.class);
        when(value.getWorkerId()).thenReturn(workerId);
        when(value.getManagerId()).thenReturn(managerId);
        when(value.getWorkerName()).thenReturn("Елена Ч.");
        when(value.getFailureDays()).thenReturn(4);
        when(value.getRating()).thenReturn(BigDecimal.valueOf(90));
        return value;
    }

    private RecipientProjection recipient(boolean eligible) {
        return recipient(eligible, 12L);
    }

    private RecipientProjection recipient(boolean eligible, long managerId) {
        return recipient(
                eligible,
                managerId,
                2L,
                "Альфия Л.",
                BigDecimal.valueOf(90)
        );
    }

    private RecipientProjection recipient(
            boolean eligible,
            long managerId,
            long workerId,
            String workerName,
            BigDecimal rating
    ) {
        RecipientProjection value = mock(RecipientProjection.class);
        when(value.getWorkerId()).thenReturn(workerId);
        when(value.getManagerId()).thenReturn(managerId);
        when(value.getWorkerName()).thenReturn(workerName);
        when(value.getRating()).thenReturn(rating);
        when(value.getHundredPercentDays()).thenReturn(14);
        when(value.getFailureDays()).thenReturn(0);
        when(value.getEstimatedRemainingMinutes()).thenReturn(0L);
        when(value.getAcceptsCompanyTransfers()).thenReturn(true);
        when(value.getRecipientEligible()).thenReturn(eligible);
        when(value.getWorkerGroupConnected()).thenReturn(true);
        return value;
    }

    private ScenarioResult simulateEmergency(
            boolean includePendingCard,
            boolean recipientEligible
    ) throws Exception {
        WorkloadShadowTransferRepository repository =
                mock(WorkloadShadowTransferRepository.class);
        WorkloadShadowSettingsService settingsService =
                mock(WorkloadShadowSettingsService.class);
        WorkloadTransferGraphQueryService graphQueryService =
                mock(WorkloadTransferGraphQueryService.class);
        WorkloadShadowSettingsResponse settings = mock(WorkloadShadowSettingsResponse.class);
        when(settingsService.current()).thenReturn(settings);
        when(settings.allowedFailureDays()).thenReturn(3);
        when(settings.fourthFailurePercent()).thenReturn(15);
        when(settings.fourthFailureMaxCompanies()).thenReturn(1);
        when(settings.recipientMinimumRating()).thenReturn(85);
        SourceWorkerProjection source = source(1L, 11L);
        RecipientProjection recipient = recipient(recipientEligible);
        when(repository.findSourceWorkers(3)).thenReturn(List.of(source));
        when(repository.findRecipients()).thenReturn(List.of(recipient));
        when(graphQueryService.findActiveGraphs(anyList(), any(LocalDate.class)))
                .thenReturn(Map.of(1L, List.of(healthyGraph(includePendingCard))));

        ObjectMapper objectMapper = new ObjectMapper();
        WorkloadShadowTransferSimulationService service =
                new WorkloadShadowTransferSimulationService(
                        repository,
                        settingsService,
                        graphQueryService,
                        objectMapper
                );
        service.rebuild(77L, LocalDateTime.of(2026, 7, 27, 22, 30));

        ArgumentCaptor<String> casesJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventsJson = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertTransferCases(casesJson.capture(), anyLong(), any());
        verify(repository).upsertEvents(eventsJson.capture(), any(), any());
        return new ScenarioResult(
                objectMapper.readTree(casesJson.getValue()).get(0),
                objectMapper.readTree(eventsJson.getValue())
        );
    }

    private boolean hasEvent(JsonNode events, String eventType) {
        for (JsonNode event : events) {
            if (eventType.equals(event.get("eventType").asText())) {
                return true;
            }
        }
        return false;
    }

    private String eventMessage(JsonNode events, String eventType) {
        for (JsonNode event : events) {
            if (eventType.equals(event.get("eventType").asText())) {
                return event.get("message").asText();
            }
        }
        throw new AssertionError("Событие " + eventType + " не найдено");
    }

    private int countEvents(JsonNode events, String eventType) {
        int count = 0;
        for (JsonNode event : events) {
            if (eventType.equals(event.get("eventType").asText())) {
                count++;
            }
        }
        return count;
    }

    private JsonNode caseWithStatus(JsonNode cases, String status) {
        for (JsonNode transferCase : cases) {
            if (status.equals(transferCase.get("caseStatus").asText())) {
                return transferCase;
            }
        }
        throw new AssertionError("Кейс со статусом " + status + " не найден");
    }

    private long firstCandidateWorker(JsonNode candidates, String caseKey) {
        for (JsonNode candidate : candidates) {
            if (caseKey.equals(candidate.get("caseKey").asText())
                    && candidate.get("sequenceNumber").asInt() == 1) {
                return candidate.get("workerId").asLong();
            }
        }
        throw new AssertionError("Первый кандидат для " + caseKey + " не найден");
    }

    private SimulationPayload simulateTransferGraphs(
            List<WorkloadTransferCompanyGraph> graphs
    ) throws Exception {
        return simulateTransferGraphs(graphs, List.of());
    }

    private SimulationPayload simulateTransferGraphs(
            List<WorkloadTransferCompanyGraph> graphs,
            List<RecipientProjection> recipients
    ) throws Exception {
        return simulateTransferGraphs(graphs, recipients, 15, 1);
    }

    private SimulationPayload simulateTransferGraphs(
            List<WorkloadTransferCompanyGraph> graphs,
            List<RecipientProjection> recipients,
            int transferPercent,
            int maxCompanies
    ) throws Exception {
        WorkloadShadowTransferRepository repository =
                mock(WorkloadShadowTransferRepository.class);
        WorkloadShadowSettingsService settingsService =
                mock(WorkloadShadowSettingsService.class);
        WorkloadTransferGraphQueryService graphQueryService =
                mock(WorkloadTransferGraphQueryService.class);
        WorkloadShadowSettingsResponse settings = mock(WorkloadShadowSettingsResponse.class);
        when(settingsService.current()).thenReturn(settings);
        when(settings.allowedFailureDays()).thenReturn(3);
        when(settings.fourthFailurePercent()).thenReturn(transferPercent);
        when(settings.fourthFailureMaxCompanies()).thenReturn(maxCompanies);
        when(settings.newMinutesPerCard()).thenReturn(5);
        SourceWorkerProjection source = source(1L, 11L);
        when(repository.findSourceWorkers(3)).thenReturn(List.of(source));
        when(repository.findRecipients()).thenReturn(recipients);
        when(graphQueryService.findActiveGraphs(anyList(), any(LocalDate.class)))
                .thenReturn(Map.of(1L, graphs));

        ObjectMapper objectMapper = new ObjectMapper();
        WorkloadShadowTransferSimulationService service =
                new WorkloadShadowTransferSimulationService(
                        repository,
                        settingsService,
                        graphQueryService,
                        objectMapper
                );
        service.rebuild(77L, LocalDateTime.of(2026, 7, 27, 22, 30));

        ArgumentCaptor<String> casesJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventsJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> candidatesJson = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertTransferCases(casesJson.capture(), anyLong(), any());
        if (recipients.isEmpty()) {
            verify(repository, never()).upsertCandidates(anyString());
        } else {
            verify(repository).upsertCandidates(candidatesJson.capture());
        }
        verify(repository).upsertEvents(eventsJson.capture(), any(), any());
        return new SimulationPayload(
                objectMapper.readTree(casesJson.getValue()),
                objectMapper.readTree(eventsJson.getValue()),
                recipients.isEmpty()
                        ? objectMapper.createArrayNode()
                        : objectMapper.readTree(candidatesJson.getValue())
        );
    }

    private WorkloadTransferCompanyGraph transferGraph(
            long companyId,
            boolean sharedOwnership,
            long otherWorkerActiveOrderCount,
            List<Warning> warnings
    ) {
        WorkloadTotals totals = new WorkloadTotals(
                1, 0, 1, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 5
        );
        OrderNode order = new OrderNode(
                companyId * 10,
                "Новый",
                1L,
                11L,
                false,
                false,
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 7, 27),
                1,
                1,
                1,
                1,
                1,
                0,
                List.of(),
                List.of(),
                List.of(),
                totals,
                List.of()
        );
        return new WorkloadTransferCompanyGraph(
                companyId,
                "Компания #" + companyId,
                true,
                "В работе",
                11L,
                true,
                sharedOwnership ? List.of(1L, 2L) : List.of(1L),
                sharedOwnership,
                otherWorkerActiveOrderCount,
                0,
                List.of(order),
                List.of(),
                List.of(),
                List.of(),
                totals,
                warnings
        );
    }

    private WorkloadTransferCompanyGraph healthyGraph(boolean includePendingCard) {
        WorkloadTotals totals = new WorkloadTotals(
                1, includePendingCard ? 1 : 0, 1, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 5
        );
        List<ReviewNode> reviews = includePendingCard
                ? List.of(new ReviewNode(
                        500L,
                        1000L,
                        1L,
                        null,
                        null,
                        null,
                        null,
                        ReviewStage.NAGUL,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        0,
                        null,
                        0,
                        0,
                        0,
                        List.of()
                ))
                : List.of();
        OrderNode order = new OrderNode(
                1000L,
                "Новый",
                1L,
                11L,
                false,
                false,
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 7, 27),
                1,
                1,
                1,
                includePendingCard ? 1 : 0,
                1,
                0,
                reviews,
                List.of(),
                List.of(),
                totals,
                List.of()
        );
        return new WorkloadTransferCompanyGraph(
                100L,
                "Компания",
                true,
                "В работе",
                11L,
                true,
                List.of(1L),
                false,
                0,
                0,
                List.of(order),
                List.of(),
                List.of(),
                List.of(),
                totals,
                List.of()
        );
    }

    private WorkloadTransferCompanyGraph brokenGraph() {
        return brokenGraph(100L);
    }

    private WorkloadTransferCompanyGraph brokenGraph(long companyId) {
        Warning warning = new Warning(
                WarningCode.COMPANY_MANAGER_MISMATCH,
                WarningSeverity.ERROR,
                "Менеджер компании не совпадает"
        );
        return transferGraph(companyId, false, 0, List.of(warning));
    }

    private record ScenarioResult(JsonNode transferCase, JsonNode events) {
    }

    private record SimulationPayload(JsonNode cases, JsonNode events, JsonNode candidates) {
    }
}
