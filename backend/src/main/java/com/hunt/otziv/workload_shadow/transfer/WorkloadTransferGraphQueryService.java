package com.hunt.otziv.workload_shadow.transfer;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.BadProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.CompanyOrderOwnershipProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.CompanyWorkerLinkProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.DetailProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.ExternalCheckCountProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.OrderProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.PerformerCountProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.RecoveryProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.ReviewProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferGraphRepository.SourceCompanyProjection;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowSettingsService;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.BadRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.CompanyOrderOwnershipRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.CompanyRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.CompanyWorkerLinkRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.DetailRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.EstimateRates;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.ExternalCheckCountRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.OrderRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.PerformerCountRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.RecoveryRow;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphData.ReviewRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds read-only transfer graphs for every source worker in one bulk pass.
 *
 * <p>The database work is bounded to ten set-based repository queries per run:
 * source companies, company links, ownership, orders, details, reviews, recoveries,
 * bad tasks, performer counts and external checks. Empty dependent sets skip their
 * query. Graph assembly and per-source separation happen entirely in memory.</p>
 */
@Service
@RequiredArgsConstructor
public class WorkloadTransferGraphQueryService {

    private static final int DEFAULT_NAGUL_LOOKAHEAD_DAYS = 14;

    private final WorkloadTransferGraphRepository repository;
    private final AppSettingService appSettingService;
    private final WorkloadShadowSettingsService shadowSettingsService;

    @Transactional(readOnly = true)
    public Map<Long, List<WorkloadTransferCompanyGraph>> findActiveGraphs(
            Collection<Long> requestedSourceWorkerIds,
            LocalDate date
    ) {
        Objects.requireNonNull(date, "date");
        List<Long> sourceWorkerIds = normalizedIds(requestedSourceWorkerIds);
        if (sourceWorkerIds.isEmpty()) {
            return Map.of();
        }

        WorkloadShadowSettingsResponse settings = shadowSettingsService.current();
        int lookaheadDays = Math.max(
                0,
                appSettingService.getInt(
                        AppSettingService.NAGUL_LOOKAHEAD_DAYS,
                        DEFAULT_NAGUL_LOOKAHEAD_DAYS
                )
        );
        LocalDate nagulLookaheadDate = date.plusDays(lookaheadDays);

        List<SourceCompanyProjection> sourceCompanies =
                repository.findSourceCompanies(sourceWorkerIds);
        if (sourceCompanies.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> managerBySource = new LinkedHashMap<>();
        Map<Long, List<CompanyRow>> companiesBySource = new LinkedHashMap<>();
        Set<Long> companyIds = new LinkedHashSet<>();
        for (SourceCompanyProjection value : sourceCompanies) {
            if (value.getSourceWorkerId() == null
                    || value.getManagerId() == null
                    || value.getCompanyId() == null) {
                continue;
            }
            managerBySource.put(value.getSourceWorkerId(), value.getManagerId());
            companiesBySource.computeIfAbsent(
                    value.getSourceWorkerId(),
                    ignored -> new ArrayList<>()
            ).add(new CompanyRow(
                    value.getCompanyId(),
                    value.getCompanyTitle(),
                    booleanValue(value.getCompanyActive()),
                    value.getCompanyStatus(),
                    value.getCompanyManagerId()
            ));
            companyIds.add(value.getCompanyId());
        }
        if (companiesBySource.isEmpty()) {
            return Map.of();
        }

        List<CompanyWorkerLinkRow> companyWorkerLinks =
                repository.findCompanyWorkerLinks(companyIds).stream()
                        .filter(value -> value.getCompanyId() != null && value.getWorkerId() != null)
                        .map(this::companyWorkerLink)
                        .toList();
        List<CompanyOrderOwnershipRow> companyOrderOwnership =
                repository.findCompanyOrderOwnership(companyIds).stream()
                        .filter(value -> value.getCompanyId() != null)
                        .map(this::companyOrderOwnership)
                        .toList();

        Map<Long, List<OrderRow>> ordersBySource = new LinkedHashMap<>();
        for (OrderProjection value : repository.findActiveOrders(sourceWorkerIds, companyIds)) {
            if (value.getSourceWorkerId() == null
                    || value.getOrderId() == null
                    || value.getCompanyId() == null) {
                continue;
            }
            ordersBySource.computeIfAbsent(
                    value.getSourceWorkerId(),
                    ignored -> new ArrayList<>()
            ).add(order(value));
        }
        Set<Long> orderIds = ordersBySource.values().stream()
                .flatMap(Collection::stream)
                .map(OrderRow::orderId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<DetailRow> details = orderIds.isEmpty()
                ? List.of()
                : repository.findOrderDetails(orderIds).stream()
                        .filter(value -> value.getOrderId() != null)
                        .map(this::detail)
                        .toList();

        Map<Long, List<ReviewRow>> reviewsBySource = new LinkedHashMap<>();
        for (ReviewProjection value : repository.findUnpublishedReviews(
                sourceWorkerIds,
                companyIds
        )) {
            if (value.getSourceWorkerId() == null
                    || value.getReviewId() == null
                    || value.getOrderId() == null
                    || value.getCompanyId() == null) {
                continue;
            }
            reviewsBySource.computeIfAbsent(
                    value.getSourceWorkerId(),
                    ignored -> new ArrayList<>()
            ).add(review(value));
        }

        Map<Long, List<RecoveryRow>> recoveryBySource = new LinkedHashMap<>();
        for (RecoveryProjection value : repository.findOpenRecoveryTasks(
                sourceWorkerIds,
                companyIds
        )) {
            if (value.getSourceWorkerId() == null
                    || value.getTaskId() == null
                    || value.getCompanyId() == null) {
                continue;
            }
            recoveryBySource.computeIfAbsent(
                    value.getSourceWorkerId(),
                    ignored -> new ArrayList<>()
            ).add(recovery(value));
        }

        Map<Long, List<BadRow>> badBySource = new LinkedHashMap<>();
        for (BadProjection value : repository.findOpenBadTasks(sourceWorkerIds, companyIds)) {
            if (value.getSourceWorkerId() == null
                    || value.getTaskId() == null
                    || value.getOrderId() == null
                    || value.getCompanyId() == null) {
                continue;
            }
            badBySource.computeIfAbsent(
                    value.getSourceWorkerId(),
                    ignored -> new ArrayList<>()
            ).add(bad(value));
        }

        Set<Long> reviewIds = reviewsBySource.values().stream()
                .flatMap(Collection::stream)
                .map(ReviewRow::reviewId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<PerformerCountRow> performerCounts = reviewIds.isEmpty()
                ? List.of()
                : repository.findActivePerformerCounts(reviewIds).stream()
                        .filter(value -> value.getReviewId() != null)
                        .map(this::performerCount)
                        .toList();
        List<ExternalCheckCountRow> externalCheckCounts = reviewIds.isEmpty()
                ? List.of()
                : repository.findExternalCheckCounts(reviewIds).stream()
                        .filter(value -> value.getReviewId() != null)
                        .map(this::externalCheckCount)
                        .toList();

        Map<Long, List<CompanyWorkerLinkRow>> linksByCompany =
                groupByCompany(companyWorkerLinks, CompanyWorkerLinkRow::companyId);
        Map<Long, List<CompanyOrderOwnershipRow>> ownershipByCompany =
                groupByCompany(companyOrderOwnership, CompanyOrderOwnershipRow::companyId);
        Map<Long, List<DetailRow>> detailsByOrder =
                groupByCompany(details, DetailRow::orderId);
        Map<Long, List<PerformerCountRow>> performersByReview =
                groupByCompany(performerCounts, PerformerCountRow::reviewId);
        Map<Long, List<ExternalCheckCountRow>> externalByReview =
                groupByCompany(externalCheckCounts, ExternalCheckCountRow::reviewId);

        EstimateRates rates = new EstimateRates(
                settings.newMinutesPerCard(),
                settings.correctionMinutesPerOrder(),
                Math.max(
                        WorkloadShadowSettingsService.HARD_MINIMUM_WALK_MINUTES,
                        settings.walkMinutesPerCard()
                ),
                settings.publishMinutesPerCard(),
                settings.recoveryMinutesPerTask(),
                settings.badMinutesPerTask()
        );

        Map<Long, List<WorkloadTransferCompanyGraph>> result = new LinkedHashMap<>();
        companiesBySource.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    long sourceWorkerId = entry.getKey();
                    long managerId = managerBySource.get(sourceWorkerId);
                    List<CompanyRow> companies = entry.getValue().stream()
                            .sorted(Comparator.comparingLong(CompanyRow::companyId))
                            .toList();
                    Set<Long> sourceCompanyIds = companies.stream()
                            .map(CompanyRow::companyId)
                            .collect(LinkedHashSet::new, Set::add, Set::addAll);
                    List<OrderRow> orders = ordersBySource.getOrDefault(
                            sourceWorkerId,
                            List.of()
                    ).stream().filter(value -> sourceCompanyIds.contains(value.companyId())).toList();
                    Set<Long> sourceOrderIds = orders.stream()
                            .map(OrderRow::orderId)
                            .collect(LinkedHashSet::new, Set::add, Set::addAll);
                    List<ReviewRow> reviews = reviewsBySource.getOrDefault(
                            sourceWorkerId,
                            List.of()
                    ).stream().filter(value -> sourceCompanyIds.contains(value.companyId())).toList();
                    Set<Long> sourceReviewIds = reviews.stream()
                            .map(ReviewRow::reviewId)
                            .collect(LinkedHashSet::new, Set::add, Set::addAll);

                    WorkloadTransferGraphData data = new WorkloadTransferGraphData(
                            sourceWorkerId,
                            managerId,
                            date,
                            nagulLookaheadDate,
                            rates,
                            companies,
                            flatten(sourceCompanyIds, linksByCompany),
                            flatten(sourceCompanyIds, ownershipByCompany),
                            orders,
                            flatten(sourceOrderIds, detailsByOrder),
                            reviews,
                            recoveryBySource.getOrDefault(sourceWorkerId, List.of()).stream()
                                    .filter(value -> sourceCompanyIds.contains(value.companyId()))
                                    .toList(),
                            badBySource.getOrDefault(sourceWorkerId, List.of()).stream()
                                    .filter(value -> sourceCompanyIds.contains(value.companyId()))
                                    .toList(),
                            flatten(sourceReviewIds, performersByReview),
                            flatten(sourceReviewIds, externalByReview)
                    );
                    result.put(
                            sourceWorkerId,
                            WorkloadTransferGraphAssembler.assemble(data)
                    );
                });
        return Map.copyOf(result);
    }

    private List<Long> normalizedIds(Collection<Long> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return List.of();
        }
        return requestedIds.stream()
                .filter(Objects::nonNull)
                .filter(value -> value > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private CompanyWorkerLinkRow companyWorkerLink(CompanyWorkerLinkProjection value) {
        return new CompanyWorkerLinkRow(value.getCompanyId(), value.getWorkerId());
    }

    private CompanyOrderOwnershipRow companyOrderOwnership(
            CompanyOrderOwnershipProjection value
    ) {
        return new CompanyOrderOwnershipRow(
                value.getCompanyId(),
                value.getWorkerId(),
                longValue(value.getActiveOrderCount())
        );
    }

    private OrderRow order(OrderProjection value) {
        return new OrderRow(
                value.getOrderId(),
                value.getCompanyId(),
                value.getOrderStatus(),
                value.getWorkerId(),
                value.getManagerId(),
                booleanValue(value.getWaitingForClient()),
                booleanValue(value.getClientTextExpected()),
                value.getCreatedDate(),
                value.getChangedDate(),
                intValue(value.getDeclaredOrderUnits())
        );
    }

    private DetailRow detail(DetailProjection value) {
        return new DetailRow(
                value.getOrderId(),
                intValue(value.getDeclaredUnits()),
                intValue(value.getActualReviewCount()),
                intValue(value.getPendingReviewCount())
        );
    }

    private ReviewRow review(ReviewProjection value) {
        return new ReviewRow(
                value.getReviewId(),
                value.getOrderId(),
                value.getCompanyId(),
                value.getWorkerId(),
                value.getBotId(),
                nullableBoolean(value.getBotActive()),
                value.getBotOwnerWorkerId(),
                value.getPublicationDate(),
                booleanValue(value.getWalked()),
                booleanValue(value.getTextReady()),
                booleanValue(value.getOrderWaitingForClient()),
                longValue(value.getActiveBotReviewCount()),
                value.getAccountWalkDelayBotId()
        );
    }

    private RecoveryRow recovery(RecoveryProjection value) {
        return new RecoveryRow(
                value.getTaskId(),
                value.getOrderId(),
                value.getCompanyId(),
                value.getArchiveCompanyId(),
                value.getWorkerId(),
                value.getTaskManagerId(),
                value.getBatchManagerId(),
                value.getBotId(),
                nullableBoolean(value.getBotActive()),
                value.getScheduledDate(),
                booleanValue(value.getArchivedSource()),
                value.getOrderWorkerId(),
                booleanValue(value.getOrderComplete())
        );
    }

    private BadRow bad(BadProjection value) {
        return new BadRow(
                value.getTaskId(),
                value.getOrderId(),
                value.getCompanyId(),
                value.getSourceReviewId(),
                value.getWorkerId(),
                value.getBotId(),
                nullableBoolean(value.getBotActive()),
                value.getScheduledDate()
        );
    }

    private PerformerCountRow performerCount(PerformerCountProjection value) {
        return new PerformerCountRow(
                value.getReviewId(),
                longValue(value.getActiveCount())
        );
    }

    private ExternalCheckCountRow externalCheckCount(ExternalCheckCountProjection value) {
        return new ExternalCheckCountRow(
                value.getReviewId(),
                longValue(value.getActiveCount()),
                longValue(value.getAttentionCount())
        );
    }

    private static <T> Map<Long, List<T>> groupByCompany(
            Collection<T> values,
            java.util.function.ToLongFunction<T> key
    ) {
        Map<Long, List<T>> result = new LinkedHashMap<>();
        for (T value : values) {
            result.computeIfAbsent(key.applyAsLong(value), ignored -> new ArrayList<>())
                    .add(value);
        }
        return result;
    }

    private static <T> List<T> flatten(
            Collection<Long> keys,
            Map<Long, List<T>> valuesByKey
    ) {
        List<T> result = new ArrayList<>();
        for (Long key : keys) {
            result.addAll(valuesByKey.getOrDefault(key, List.of()));
        }
        return List.copyOf(result);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value instanceof Number number && number.intValue() != 0;
    }

    private static Boolean nullableBoolean(Object value) {
        return value == null ? null : booleanValue(value);
    }

    private static int intValue(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private static long longValue(Number value) {
        return value == null ? 0 : value.longValue();
    }
}
