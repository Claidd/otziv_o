package com.hunt.otziv.workload_shadow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowTransferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowTransferRepository.RecipientProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowTransferRepository.SourceWorkerProjection;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferSelectionPolicy.Tier;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferActionableWorkload;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferEmergencyCardSelector;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphDiagnostics;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadShadowTransferSimulationService {

    private static final DateTimeFormatter SQL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final WorkloadShadowTransferRepository repository;
    private final WorkloadShadowSettingsService settingsService;
    private final WorkloadTransferGraphQueryService graphQueryService;
    private final ObjectMapper objectMapper;

    @Transactional
    public SimulationResult rebuild(long runId, LocalDateTime observedAt) {
        WorkloadShadowSettingsResponse settings = settingsService.current();
        LocalDate date = observedAt.toLocalDate();
        List<SourceWorker> sources = sourceWorkers(settings.allowedFailureDays());
        Map<Long, List<WorkloadTransferCompanyGraph>> graphsBySource =
                graphQueryService.findActiveGraphs(
                        sources.stream().map(SourceWorker::workerId).toList(),
                        date
                );
        Map<Long, List<Recipient>> recipientsByManager = recipientsByManager();
        List<Recipient> allRecipients = recipientsByManager.values().stream()
                .flatMap(List::stream)
                .toList();

        List<TransferCaseWrite> caseWrites = new ArrayList<>();
        List<TransferCandidateWrite> candidateWrites = new ArrayList<>();
        List<TransferEventWrite> eventWrites = new ArrayList<>();
        Set<Long> reportedBlockedCompanyIds = new HashSet<>();
        for (SourceWorker source : sources) {
            List<WorkloadTransferCompanyGraph> graphs =
                    graphsBySource.getOrDefault(source.workerId(), List.of());
            List<CompanyProblem> problems = graphs.stream()
                    .map(graph -> problem(graph, settings))
                    .filter(problem -> problem.problemUnits() > 0)
                    .sorted(Comparator
                            .comparingLong(CompanyProblem::estimatedMinutes).reversed()
                            .thenComparing(Comparator
                                    .comparingLong(CompanyProblem::problemUnits)
                                    .reversed())
                            .thenComparingLong(problem -> problem.graph().companyId()))
                    .toList();
            if (problems.isEmpty()) {
                continue;
            }

            Tier tier = tier(source.failureDays(), settings);
            List<CompanyProblem> transferableProblems = problems.stream()
                    .filter(problem -> !graphBlocksRecommendation(problem))
                    .toList();
            List<CompanyProblem> blockedProblems = problems.stream()
                    .filter(this::graphBlocksRecommendation)
                    .toList();
            // Keep recommendations useful even if the heaviest company is blocked,
            // but bound diagnostics by the same tier. Previously every blocked graph
            // was appended and "max 1 company" could produce dozens of alerts.
            List<CompanyProblem> selected = new ArrayList<>(
                    selectProblems(transferableProblems, tier)
            );
            selected.addAll(selectProblems(blockedProblems, tier));
            List<Recipient> rankedRecipients = recipientsByManager
                    .getOrDefault(source.managerId(), List.of())
                    .stream()
                    .filter(recipient -> recipient.workerId() != source.workerId())
                    .filter(Recipient::recipientEligible)
                    .filter(Recipient::workerGroupConnected)
                    .sorted(Comparator
                            .comparing(Recipient::rating).reversed()
                            .thenComparing(Recipient::hundredPercentDays, Comparator.reverseOrder())
                            .thenComparingLong(Recipient::estimatedRemainingMinutes)
                            .thenComparingLong(Recipient::workerId))
                    .toList();
            List<Recipient> emergencyPool = allRecipients.stream()
                    .filter(recipient -> recipient.workerId() != source.workerId())
                    .filter(Recipient::acceptsCompanyTransfers)
                    .filter(Recipient::recipientEligible)
                    .filter(Recipient::workerGroupConnected)
                    .filter(recipient -> recipient.rating().compareTo(
                            BigDecimal.valueOf(settings.recipientMinimumRating())
                    ) >= 0)
                    .toList();

            int recommendationRank = 0;
            int diagnosticRank = 0;
            for (CompanyProblem selectedProblem : selected) {
                boolean graphBlocked = graphBlocksRecommendation(selectedProblem);
                if (graphBlocked
                        && !reportedBlockedCompanyIds.add(
                                selectedProblem.graph().companyId()
                        )) {
                    continue;
                }
                int selectionRank = graphBlocked
                        ? ++diagnosticRank
                        : ++recommendationRank;
                boolean staffingRequired = !graphBlocked && rankedRecipients.isEmpty();
                Long fallbackReviewId = staffingRequired
                        ? emergencyReviewId(selectedProblem.graph())
                        : null;
                Long fallbackWorkerId = staffingRequired && fallbackReviewId != null
                        ? chooseFallback(
                                emergencyPool,
                                source,
                                selectedProblem.graph().companyId(),
                                date
                        )
                        : null;
                String caseKey = caseKey(source, selectedProblem);
                caseWrites.add(transferCase(
                        caseKey,
                        source,
                        selectedProblem,
                        tier,
                        selectionRank,
                        graphBlocked ? 0 : rankedRecipients.size(),
                        graphBlocked,
                        staffingRequired,
                        fallbackWorkerId,
                        fallbackReviewId
                ));
                int sequence = 0;
                for (Recipient recipient : graphBlocked ? List.<Recipient>of() : rankedRecipients) {
                    sequence++;
                    candidateWrites.add(new TransferCandidateWrite(
                            caseKey,
                            recipient.workerId(),
                            sequence,
                            recipient.rating(),
                            recipient.hundredPercentDays(),
                            recipient.failureDays(),
                            recipient.estimatedRemainingMinutes(),
                            recipient.workerGroupConnected()
                    ));
                }

                if (selectedProblem.diagnostics().hasReportableIssues()) {
                    eventWrites.add(event(
                            "TRANSFER_GRAPH_WARNING:"
                                    + source.workerId() + ":" + selectedProblem.graph().companyId(),
                            selectedProblem.diagnostics().errorCount() > 0 ? "CRITICAL" : "WARNING",
                            "TRANSFER_GRAPH_WARNING",
                            source,
                            settings,
                            selectedProblem.graph().companyId(),
                            caseKey,
                            "Обнаружены несогласованности пакета заказов специалиста",
                            graphWarningMessage(selectedProblem),
                            observedAt
                    ));
                }

                if (graphBlocked) {
                    continue;
                }
                if (staffingRequired) {
                    eventWrites.add(event(
                            "STAFFING:" + source.workerId() + ":" + selectedProblem.graph().companyId(),
                            "CRITICAL",
                            "STAFFING_REQUIRED",
                            source,
                            settings,
                            selectedProblem.graph().companyId(),
                            caseKey,
                            "Наблюдение: менеджеру может потребоваться новый специалист",
                            staffingMessage(
                                    source,
                                    selectedProblem,
                                    fallbackWorkerId,
                                    fallbackReviewId
                            ),
                            observedAt
                    ));
                    if (fallbackWorkerId != null && fallbackWorkerId != source.workerId()) {
                        eventWrites.add(event(
                                "EMERGENCY:" + source.workerId() + ":" + selectedProblem.graph().companyId(),
                                "WARNING",
                                "EMERGENCY_FALLBACK",
                                source,
                                settings,
                                selectedProblem.graph().companyId(),
                                caseKey,
                                "Резервный исполнитель для одиночной карточки",
                                "НАБЛЮДЕНИЕ. Если никто не примет предложение, карточку #"
                                        + (fallbackReviewId == null ? "не определена" : fallbackReviewId)
                                        + " система рекомендовала бы назначить специалисту #"
                                        + fallbackWorkerId + ". Остальные заказы компании при этом не передаются.",
                                observedAt
                        ));
                    }
                } else {
                    eventWrites.add(event(
                            "TRANSFER:" + source.workerId() + ":" + selectedProblem.graph().companyId(),
                            "WARNING",
                            "TRANSFER_RECOMMENDATION",
                            source,
                            settings,
                            selectedProblem.graph().companyId(),
                            caseKey,
                            "Подготовлена теневая рекомендация передачи компании",
                            recommendationMessage(source, selectedProblem, tier, rankedRecipients),
                            observedAt
                    ));
                }
            }
        }

        repository.deactivateTransferCases(observedAt);
        if (!caseWrites.isEmpty()) {
            String casesJson = json(caseWrites);
            String candidatesJson = json(candidateWrites);
            repository.upsertTransferCases(casesJson, runId, observedAt);
            repository.deleteStaleCandidates(casesJson, candidatesJson);
            if (!candidateWrites.isEmpty()) {
                repository.upsertCandidates(candidatesJson);
            }
        }
        repository.deleteInactiveCandidates();
        if (!eventWrites.isEmpty()) {
            repository.upsertEvents(
                    json(eventWrites),
                    observedAt,
                    observedAt.minusMinutes(Math.max(5, settings.alertCooldownMinutes()))
            );
        }
        repository.deactivateUnseenEvents(observedAt);
        return new SimulationResult(caseWrites.size(), eventWrites.size());
    }

    private List<SourceWorker> sourceWorkers(int allowedFailureDays) {
        return repository.findSourceWorkers(allowedFailureDays).stream()
                .filter(value -> value.getWorkerId() != null && value.getManagerId() != null)
                .map(this::sourceWorker)
                .toList();
    }

    private Map<Long, List<Recipient>> recipientsByManager() {
        Map<Long, List<Recipient>> result = new LinkedHashMap<>();
        for (RecipientProjection value : repository.findRecipients()) {
            if (value.getWorkerId() == null || value.getManagerId() == null) {
                continue;
            }
            Recipient recipient = recipient(value);
            result.computeIfAbsent(recipient.managerId(), ignored -> new ArrayList<>()).add(recipient);
        }
        return result;
    }

    private CompanyProblem problem(
            WorkloadTransferCompanyGraph graph,
            WorkloadShadowSettingsResponse settings
    ) {
        WorkloadTransferActionableWorkload actionable = WorkloadTransferActionableWorkload.calculate(
                graph,
                new WorkloadTransferActionableWorkload.EstimateRates(
                        settings.newMinutesPerCard(),
                        settings.correctionMinutesPerOrder(),
                        Math.max(
                                WorkloadShadowSettingsService.HARD_MINIMUM_WALK_MINUTES,
                                settings.walkMinutesPerCard()
                        ),
                        settings.publishMinutesPerCard(),
                        settings.recoveryMinutesPerTask(),
                        settings.badMinutesPerTask()
                )
        );
        return new CompanyProblem(
                graph,
                actionable,
                WorkloadTransferGraphDiagnostics.from(graph)
        );
    }

    private List<CompanyProblem> selectProblems(List<CompanyProblem> problems, Tier tier) {
        return WorkloadTransferSelectionPolicy.selectClosest(
                problems,
                tier.percent(),
                tier.maxCompanies(),
                CompanyProblem::problemUnits,
                problem -> problem.graph().companyId()
        );
    }

    private Tier tier(int failureDays, WorkloadShadowSettingsResponse settings) {
        return WorkloadTransferSelectionPolicy.tier(
                failureDays,
                settings.allowedFailureDays(),
                new Tier(settings.fourthFailurePercent(), settings.fourthFailureMaxCompanies()),
                new Tier(settings.fifthFailurePercent(), settings.fifthFailureMaxCompanies()),
                new Tier(settings.sixthFailurePercent(), settings.sixthFailureMaxCompanies())
        );
    }

    private TransferCaseWrite transferCase(
            String caseKey,
            SourceWorker source,
            CompanyProblem problem,
            Tier tier,
            int selectionRank,
            int candidateCount,
            boolean graphBlocked,
            boolean staffingRequired,
            Long fallbackWorkerId,
            Long fallbackReviewId
    ) {
        WorkloadTransferCompanyGraph.WorkloadTotals totals = problem.graph().totals();
        WorkloadTransferActionableWorkload actionable = problem.actionable();
        WorkloadTransferGraphDiagnostics diagnostics = problem.diagnostics();
        return new TransferCaseWrite(
                caseKey,
                source.managerId(),
                source.workerId(),
                problem.graph().companyId(),
                problem.graph().companyTitle(),
                source.failureDays(),
                tier.percent(),
                selectionRank,
                problem.problemUnits(),
                problem.estimatedMinutes(),
                totals.activeOrderCount(),
                actionable.newUnits(),
                actionable.correctionUnits(),
                actionable.nagulUnits(),
                actionable.publishUnits(),
                actionable.recoveryUnits(),
                actionable.badUnits(),
                diagnostics.warningCount(),
                diagnostics.errorCount(),
                diagnostics.compactWarningCodes(),
                diagnostics.compactErrorCodes(),
                candidateCount,
                graphBlocked ? "BLOCKED_GRAPH" : "SHADOW_PENDING",
                staffingRequired,
                fallbackWorkerId,
                fallbackReviewId
        );
    }

    private Long chooseFallback(
            List<Recipient> emergencyPool,
            SourceWorker source,
            long companyId,
            LocalDate date
    ) {
        if (emergencyPool.isEmpty()) {
            return null;
        }
        List<Recipient> ordered = emergencyPool.stream()
                .sorted(Comparator.comparingLong(Recipient::workerId))
                .toList();
        Random stableRandom = new Random(Objects.hash(date, source.managerId(), source.workerId(), companyId));
        return ordered.get(stableRandom.nextInt(ordered.size())).workerId();
    }

    private boolean graphBlocksRecommendation(CompanyProblem problem) {
        return problem.diagnostics().errorCount() > 0;
    }

    private Long emergencyReviewId(WorkloadTransferCompanyGraph graph) {
        return WorkloadTransferEmergencyCardSelector.select(graph);
    }

    private String recommendationMessage(
            SourceWorker source,
            CompanyProblem problem,
            Tier tier,
            List<Recipient> candidates
    ) {
        String sequence = candidates.stream()
                .limit(10)
                .map(candidate -> "#" + candidate.workerId() + " (" + candidate.rating() + ")")
                .reduce((left, right) -> left + " → " + right)
                .orElse("кандидатов нет");
        return "НАБЛЮДЕНИЕ. У специалиста #" + source.workerId() + " зафиксировано "
                + source.failureDays() + " дней ниже 100%. Для компании «"
                + safeTitle(problem.graph()) + "» система подготовила бы передачу пакета заказов этого специалиста "
                + "со всеми их активными этапами. Заказы других специалистов этой компании не затрагиваются. "
                + "Текущая выполнимая нагрузка: " + problem.problemUnits()
                + " ед., около " + problem.estimatedMinutes()
                + " мин. Уровень: " + tier.percent() + "%, максимум "
                + tier.maxCompanies() + " комп. Очередь кандидатов: " + sequence
                + ". Никакие назначения не изменены.";
    }

    private String staffingMessage(
            SourceWorker source,
            CompanyProblem problem,
            Long fallbackWorkerId,
            Long fallbackReviewId
    ) {
        String fallback;
        if (fallbackReviewId == null) {
            fallback = "нет конкретной выполнимой карточки для аварийного назначения; "
                    + "текущий владелец сохраняется";
        } else if (fallbackWorkerId == null || fallbackWorkerId == source.workerId()) {
            fallback = "для карточки #" + fallbackReviewId
                    + " подходящего резервного специалиста нет; текущий владелец сохраняется";
        } else {
            fallback = "для карточки #" + fallbackReviewId
                    + " резервом выбран специалист #" + fallbackWorkerId;
        }
        return "НАБЛЮДЕНИЕ. Для компании «" + safeTitle(problem.graph())
                + "» не найден получатель, отвечающий всем правилам рейтинга, 100% и доступности. "
                + "Это сигнал о дефиците сотрудника у менеджера. " + fallback
                + ". Компания и все карточки остаются назначены текущему специалисту.";
    }

    private String graphWarningMessage(CompanyProblem problem) {
        WorkloadTransferGraphDiagnostics diagnostics = problem.diagnostics();
        String warningCodes = diagnostics.compactWarningCodes().isBlank()
                ? "нет"
                : diagnostics.compactWarningCodes();
        String errorCodes = diagnostics.compactErrorCodes().isBlank()
                ? "нет"
                : diagnostics.compactErrorCodes();
        return "НАБЛЮДЕНИЕ. В пакете заказов специалиста по компании «" + safeTitle(problem.graph())
                + "» обнаружено ошибок: " + diagnostics.errorCount()
                + ", предупреждений: " + diagnostics.warningCount()
                + ". Коды ошибок: " + errorCodes
                + ". Коды предупреждений: " + warningCodes
                + ". Передача не выполнялась; требуется проверка связей до боевого режима.";
    }

    private TransferEventWrite event(
            String deduplicationKey,
            String severity,
            String eventType,
            SourceWorker source,
            WorkloadShadowSettingsResponse settings,
            long companyId,
            String caseKey,
            String title,
            String message,
            LocalDateTime now
    ) {
        Long targetChatId = settings.notificationGroupChatId();
        boolean notificationsEnabled = settings.groupNotificationsEnabled();
        boolean routeValid = targetChatId != null && targetChatId < 0;
        return new TransferEventWrite(
                deduplicationKey,
                severity,
                eventType,
                source.managerId(),
                source.workerId(),
                companyId,
                caseKey,
                title,
                message,
                targetChatId,
                !notificationsEnabled
                        ? "SKIPPED"
                        : routeValid ? "PENDING" : "MISSING_GROUP_BINDING",
                notificationsEnabled && routeValid ? sqlDateTime(now) : null
        );
    }

    private String safeTitle(WorkloadTransferCompanyGraph graph) {
        return graph.companyTitle() == null || graph.companyTitle().isBlank()
                ? "Компания #" + graph.companyId()
                : graph.companyTitle().trim();
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String caseKey(SourceWorker source, CompanyProblem problem) {
        return source.workerId() + ":" + problem.graph().companyId();
    }

    private SourceWorker sourceWorker(SourceWorkerProjection value) {
        return new SourceWorker(
                value.getWorkerId(),
                value.getManagerId(),
                intValue(value.getFailureDays()),
                value(value.getRating())
        );
    }

    private Recipient recipient(RecipientProjection value) {
        return new Recipient(
                value.getWorkerId(),
                value.getManagerId(),
                value(value.getRating()),
                intValue(value.getHundredPercentDays()),
                intValue(value.getFailureDays()),
                longValue(value.getEstimatedRemainingMinutes()),
                booleanValue(value.getAcceptsCompanyTransfers()),
                booleanValue(value.getRecipientEligible()),
                booleanValue(value.getWorkerGroupConnected())
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Не удалось подготовить пакет теневого распределения",
                    exception
            );
        }
    }

    private String sqlDateTime(LocalDateTime value) {
        return value == null ? null : value.format(SQL_DATE_TIME);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value instanceof Number number && number.intValue() != 0;
    }

    private static int intValue(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private static long longValue(Number value) {
        return value == null ? 0 : value.longValue();
    }

    public record SimulationResult(int transferCaseCount, int eventCount) {
    }

    private record CompanyProblem(
            WorkloadTransferCompanyGraph graph,
            WorkloadTransferActionableWorkload actionable,
            WorkloadTransferGraphDiagnostics diagnostics
    ) {
        private long problemUnits() {
            return actionable.problemUnits();
        }

        private long estimatedMinutes() {
            return actionable.estimatedMinutes();
        }
    }

    private record TransferCaseWrite(
            String caseKey,
            long managerId,
            long sourceWorkerId,
            long companyId,
            String companyTitle,
            int failureNumber,
            int transferPercent,
            int selectionRank,
            long problemUnits,
            long estimatedMinutes,
            long activeOrderCount,
            long newUnitCount,
            long correctionCount,
            long nagulCount,
            long publishCount,
            long recoveryCount,
            long badCount,
            int graphWarningCount,
            int graphErrorCount,
            String graphWarningCodes,
            String graphErrorCodes,
            int candidateCount,
            String caseStatus,
            boolean staffingRequired,
            Long fallbackWorkerId,
            Long fallbackReviewId
    ) {
    }

    private record TransferCandidateWrite(
            String caseKey,
            long workerId,
            int sequenceNumber,
            BigDecimal rating,
            int hundredPercentDays,
            int failureDays,
            long currentEstimatedMinutes,
            boolean workerGroupConnected
    ) {
    }

    private record TransferEventWrite(
            String deduplicationKey,
            String severity,
            String eventType,
            long managerId,
            long workerId,
            long companyId,
            String caseKey,
            String title,
            String message,
            Long targetGroupChatId,
            String deliveryStatus,
            String nextAttemptAt
    ) {
    }

    private record SourceWorker(
            long workerId,
            long managerId,
            int failureDays,
            BigDecimal rating
    ) {
    }

    private record Recipient(
            long workerId,
            long managerId,
            BigDecimal rating,
            int hundredPercentDays,
            int failureDays,
            long estimatedRemainingMinutes,
            boolean acceptsCompanyTransfers,
            boolean recipientEligible,
            boolean workerGroupConnected
    ) {
    }
}
