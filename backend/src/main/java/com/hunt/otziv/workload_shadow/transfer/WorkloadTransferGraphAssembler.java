package com.hunt.otziv.workload_shadow.transfer;

import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.ReviewStage.NAGUL;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.ReviewStage.PUBLISH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.ARCHIVED_RECOVERY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.BAD_BOT_INACTIVE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.BAD_BOT_MISSING;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.BAD_ORDER_NOT_OWNED_BY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.BAD_WORKER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.COMPANY_INACTIVE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.COMPANY_MANAGER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.COMPLETED_RECOVERY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.DECLARED_DETAIL_AMOUNT_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.DECLARED_REVIEW_COUNT_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.EXTERNAL_CHECK_REQUIRES_ATTENTION;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.ORDER_MANAGER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.ORDER_WAITING_FOR_CLIENT;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.OTHER_WORKER_ACTIVE_ORDERS;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.RECOVERY_BOT_INACTIVE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.RECOVERY_BOT_MISSING;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.RECOVERY_MANAGER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.RECOVERY_ORDER_NOT_OWNED_BY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.RECOVERY_WORKER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_BOT_DUPLICATED;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_BOT_INACTIVE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_BOT_MISSING;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_BOT_OWNER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_BOT_STUB;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_DELAY_BOT_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_ORDER_NOT_OWNED_BY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_SUPPRESSED_BY_RECOVERY;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_TEXT_NOT_READY;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.REVIEW_WORKER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.SHARED_COMPANY_OWNERSHIP;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.SOURCE_COMPANY_LINK_MISSING;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningCode.UNASSIGNED_ACTIVE_ORDERS;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningSeverity.ERROR;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningSeverity.INFO;
import static com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WarningSeverity.WARNING;

import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.BadTaskNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.RecoveryTaskNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.ReviewNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.Warning;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WorkloadTotals;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class WorkloadTransferGraphAssembler {

    private static final String STATUS_NEW = "Новый";
    private static final String STATUS_CORRECTION = "Коррекция";
    private static final long STUB_BOT_ID = 1L;

    private WorkloadTransferGraphAssembler() {
    }

    static List<WorkloadTransferCompanyGraph> assemble(WorkloadTransferGraphData data) {
        Map<Long, CompanyAccumulator> companies = new LinkedHashMap<>();
        data.companies().stream()
                .sorted(Comparator.comparingLong(CompanyRow::companyId))
                .forEach(row -> companies.put(row.companyId(), new CompanyAccumulator(row)));
        if (companies.isEmpty()) {
            return List.of();
        }

        data.companyWorkerLinks().forEach(row -> {
            CompanyAccumulator company = companies.get(row.companyId());
            if (company != null) {
                company.linkedWorkerIds.add(row.workerId());
            }
        });
        data.companyOrderOwnership().forEach(row -> {
            CompanyAccumulator company = companies.get(row.companyId());
            if (company == null) {
                return;
            }
            if (row.workerId() == null) {
                company.unassignedActiveOrderCount = safeAdd(
                        company.unassignedActiveOrderCount,
                        row.activeOrderCount()
                );
            } else if (row.workerId() != data.sourceWorkerId()) {
                company.otherWorkerActiveOrderCount = safeAdd(
                        company.otherWorkerActiveOrderCount,
                        row.activeOrderCount()
                );
            }
        });

        Map<Long, OrderAccumulator> orders = new LinkedHashMap<>();
        data.orders().stream()
                .sorted(Comparator.comparingLong(OrderRow::orderId))
                .forEach(row -> {
                    CompanyAccumulator company = companies.get(row.companyId());
                    if (company == null) {
                        return;
                    }
                    OrderAccumulator order = new OrderAccumulator(row);
                    orders.put(row.orderId(), order);
                    company.orders.add(order);
                });

        data.details().forEach(row -> {
            OrderAccumulator order = orders.get(row.orderId());
            if (order != null) {
                order.details.add(row);
            }
        });

        Map<Long, Long> performerCounts = new HashMap<>();
        for (PerformerCountRow row : data.performerCounts()) {
            performerCounts.merge(row.reviewId(), row.activeCount(), WorkloadTransferGraphAssembler::safeAdd);
        }
        Map<Long, ExternalCheckCountRow> externalCounts = new HashMap<>();
        for (ExternalCheckCountRow row : data.externalCheckCounts()) {
            externalCounts.merge(row.reviewId(), row, (left, right) -> new ExternalCheckCountRow(
                    left.reviewId(),
                    safeAdd(left.activeCount(), right.activeCount()),
                    safeAdd(left.attentionCount(), right.attentionCount())
            ));
        }

        Set<Long> ordersSuppressedByRecovery = new LinkedHashSet<>();
        for (RecoveryRow row : data.recoveryTasks()) {
            if (!row.archivedSource() && row.orderId() != null) {
                ordersSuppressedByRecovery.add(row.orderId());
            }
        }

        for (ReviewRow row : data.reviews()) {
            CompanyAccumulator company = companies.get(row.companyId());
            if (company == null) {
                continue;
            }
            ReviewEnvelope review = new ReviewEnvelope(
                    row,
                    performerCounts.getOrDefault(row.reviewId(), 0L),
                    externalCounts.getOrDefault(row.reviewId(), new ExternalCheckCountRow(row.reviewId(), 0, 0)),
                    ordersSuppressedByRecovery.contains(row.orderId()),
                    row.activeBotReviewCount() > 1
            );
            OrderAccumulator order = orders.get(row.orderId());
            if (order == null) {
                company.detachedReviews.add(review);
            } else {
                order.reviews.add(review);
            }
        }

        for (RecoveryRow row : data.recoveryTasks()) {
            CompanyAccumulator company = companies.get(row.companyId());
            if (company == null) {
                continue;
            }
            OrderAccumulator order = row.orderId() == null ? null : orders.get(row.orderId());
            if (order == null) {
                company.detachedRecoveryTasks.add(row);
            } else {
                order.recoveryTasks.add(row);
            }
        }

        for (BadRow row : data.badTasks()) {
            CompanyAccumulator company = companies.get(row.companyId());
            if (company == null) {
                continue;
            }
            OrderAccumulator order = orders.get(row.orderId());
            if (order == null) {
                company.detachedBadTasks.add(row);
            } else {
                order.badTasks.add(row);
            }
        }

        List<WorkloadTransferCompanyGraph> result = new ArrayList<>(companies.size());
        for (CompanyAccumulator company : companies.values()) {
            result.add(buildCompany(data, company));
        }
        return List.copyOf(result);
    }

    private static WorkloadTransferCompanyGraph buildCompany(
            WorkloadTransferGraphData data,
            CompanyAccumulator company
    ) {
        List<Long> linkedWorkerIds = company.linkedWorkerIds.stream().sorted().toList();
        boolean sourceLinkPresent = linkedWorkerIds.contains(data.sourceWorkerId());
        boolean sharedOwnership = linkedWorkerIds.size() > 1;
        List<Warning> warnings = new ArrayList<>();
        if (!company.row.companyActive()) {
            warnings.add(warning(COMPANY_INACTIVE, WARNING, "Компания отключена, но у неё остались активные обязанности"));
        }
        if (!Objects.equals(company.row.managerId(), data.managerId())) {
            warnings.add(warning(
                    COMPANY_MANAGER_MISMATCH,
                    ERROR,
                    "Менеджер компании не совпадает с менеджером специалиста"
            ));
        }
        if (!sourceLinkPresent) {
            warnings.add(warning(
                    SOURCE_COMPANY_LINK_MISSING,
                    ERROR,
                    "У специалиста есть активная обязанность, но отсутствует связь workers_companies"
            ));
        }
        if (sharedOwnership) {
            warnings.add(warning(
                    SHARED_COMPANY_OWNERSHIP,
                    INFO,
                    company.otherWorkerActiveOrderCount > 0
                            ? "Компания общая для нескольких специалистов; в пакет входят только заказы исходного исполнителя: "
                                    + linkedWorkerIds
                            : "Компания имеет дополнительные связи workers_companies без активных заказов других исполнителей: "
                                    + linkedWorkerIds
            ));
        }
        if (company.otherWorkerActiveOrderCount > 0) {
            warnings.add(warning(
                    OTHER_WORKER_ACTIVE_ORDERS,
                    INFO,
                    "Активные заказы других специалистов не входят в передаваемый пакет: "
                            + company.otherWorkerActiveOrderCount
            ));
        }
        if (company.unassignedActiveOrderCount > 0) {
            warnings.add(warning(
                    UNASSIGNED_ACTIVE_ORDERS,
                    WARNING,
                    "В компании есть активные заказы без специалиста; они не входят в пакет: "
                            + company.unassignedActiveOrderCount
            ));
        }

        List<OrderNode> orderNodes = company.orders.stream()
                .sorted(Comparator.comparingLong(value -> value.row.orderId()))
                .map(order -> buildOrder(data, order))
                .toList();
        List<ReviewNode> detachedReviews = company.detachedReviews.stream()
                .sorted(Comparator.comparingLong(value -> value.row.reviewId()))
                .map(value -> buildReview(data, value, true))
                .toList();
        List<RecoveryTaskNode> detachedRecovery = company.detachedRecoveryTasks.stream()
                .sorted(Comparator.comparingLong(RecoveryRow::taskId))
                .map(value -> buildRecovery(data, value, true))
                .toList();
        List<BadTaskNode> detachedBad = company.detachedBadTasks.stream()
                .sorted(Comparator.comparingLong(BadRow::taskId))
                .map(value -> buildBad(data, value, true))
                .toList();

        TotalsCounter totals = new TotalsCounter(data.estimateRates());
        orderNodes.forEach(node -> totals.add(node.totals()));
        detachedReviews.forEach(totals::addReview);
        detachedRecovery.forEach(value -> totals.addRecovery());
        detachedBad.forEach(value -> totals.addBad());

        return new WorkloadTransferCompanyGraph(
                company.row.companyId(),
                company.row.companyTitle(),
                company.row.companyActive(),
                company.row.companyStatus(),
                data.managerId(),
                sourceLinkPresent,
                linkedWorkerIds,
                sharedOwnership,
                company.otherWorkerActiveOrderCount,
                company.unassignedActiveOrderCount,
                orderNodes,
                detachedReviews,
                detachedRecovery,
                detachedBad,
                totals.toValue(),
                warnings
        );
    }

    private static OrderNode buildOrder(WorkloadTransferGraphData data, OrderAccumulator order) {
        int detailCount = order.details.size();
        int declaredDetailUnits = safeInt(order.details.stream()
                .mapToLong(DetailRow::declaredUnits)
                .sum());
        int actualReviewCards = safeInt(order.details.stream()
                .mapToLong(DetailRow::actualReviewCount)
                .sum());
        int pendingReviewCards = safeInt(order.details.stream()
                .mapToLong(DetailRow::pendingReviewCount)
                .sum());
        int newUnits = isStatus(order.row.status(), STATUS_NEW)
                ? pendingReviewCards
                : 0;
        int correctionUnits = isStatus(order.row.status(), STATUS_CORRECTION) ? 1 : 0;

        List<Warning> warnings = new ArrayList<>();
        if (!Objects.equals(order.row.managerId(), data.managerId())) {
            warnings.add(warning(
                    ORDER_MANAGER_MISMATCH,
                    ERROR,
                    "Менеджер заказа не совпадает с менеджером компании"
            ));
        }
        if (order.row.waitingForClient()) {
            warnings.add(warning(
                    ORDER_WAITING_FOR_CLIENT,
                    INFO,
                    "Заказ ожидает клиента и должен исключаться из выполнимого дневного плана"
            ));
        }
        if (order.row.declaredOrderUnits() > 0
                && declaredDetailUnits > 0
                && order.row.declaredOrderUnits() != declaredDetailUnits) {
            warnings.add(warning(
                    DECLARED_DETAIL_AMOUNT_MISMATCH,
                    WARNING,
                    "order_amount=" + order.row.declaredOrderUnits()
                            + ", сумма order_details.amount=" + declaredDetailUnits
            ));
        }
        int declaredForComparison = declaredDetailUnits > 0
                ? declaredDetailUnits
                : order.row.declaredOrderUnits();
        if (actualReviewCards > 0
                && declaredForComparison > 0
                && actualReviewCards != declaredForComparison) {
            warnings.add(warning(
                    DECLARED_REVIEW_COUNT_MISMATCH,
                    WARNING,
                    "Фактических карточек " + actualReviewCards + ", заявлено " + declaredForComparison
            ));
        }

        List<ReviewNode> reviews = order.reviews.stream()
                .sorted(Comparator.comparingLong(value -> value.row.reviewId()))
                .map(value -> buildReview(data, value, false))
                .toList();
        List<RecoveryTaskNode> recoveryTasks = order.recoveryTasks.stream()
                .sorted(Comparator.comparingLong(RecoveryRow::taskId))
                .map(value -> buildRecovery(data, value, false))
                .toList();
        List<BadTaskNode> badTasks = order.badTasks.stream()
                .sorted(Comparator.comparingLong(BadRow::taskId))
                .map(value -> buildBad(data, value, false))
                .toList();

        TotalsCounter totals = new TotalsCounter(data.estimateRates());
        totals.activeOrderCount = 1;
        totals.newUnits = newUnits;
        totals.correctionUnits = correctionUnits;
        reviews.forEach(totals::addReview);
        recoveryTasks.forEach(value -> totals.addRecovery());
        badTasks.forEach(value -> totals.addBad());

        return new OrderNode(
                order.row.orderId(),
                order.row.status(),
                order.row.workerId(),
                order.row.managerId(),
                order.row.waitingForClient(),
                order.row.clientTextExpected(),
                order.row.createdDate(),
                order.row.changedDate(),
                order.row.declaredOrderUnits(),
                declaredDetailUnits,
                detailCount,
                actualReviewCards,
                newUnits,
                correctionUnits,
                reviews,
                recoveryTasks,
                badTasks,
                totals.toValue(),
                warnings
        );
    }

    private static ReviewNode buildReview(
            WorkloadTransferGraphData data,
            ReviewEnvelope value,
            boolean detached
    ) {
        ReviewRow row = value.row;
        boolean nagul = !row.walked();
        boolean dueOnDate = row.publicationDate() != null && !row.publicationDate().isAfter(data.date());
        boolean futureWithinLookahead = nagul
                && row.publicationDate() != null
                && row.publicationDate().isAfter(data.date())
                && !row.publicationDate().isAfter(data.nagulLookaheadDate());
        boolean outsideLookahead = nagul
                && (row.publicationDate() == null || row.publicationDate().isAfter(data.nagulLookaheadDate()));

        List<Warning> warnings = new ArrayList<>();
        if (!Objects.equals(row.workerId(), data.sourceWorkerId())) {
            warnings.add(warning(
                    REVIEW_WORKER_MISMATCH,
                    ERROR,
                    "Исполнитель карточки не совпадает с исполнителем активного заказа"
            ));
        }
        if (detached) {
            warnings.add(warning(
                    REVIEW_ORDER_NOT_OWNED_BY_SOURCE,
                    ERROR,
                    "Карточка назначена специалисту, но её активный заказ принадлежит другому специалисту"
            ));
            if (row.orderWaitingForClient()) {
                warnings.add(warning(
                        ORDER_WAITING_FOR_CLIENT,
                        INFO,
                        "Заказ отсоединённой карточки ожидает клиента; карточка исключена из выполнимой нагрузки"
                ));
            }
        }
        if (!row.textReady()) {
            warnings.add(warning(
                    REVIEW_TEXT_NOT_READY,
                    INFO,
                    "Текст карточки ещё не готов и карточка не показывается в рабочей вкладке"
            ));
        }
        if (value.suppressedByRecovery) {
            warnings.add(warning(
                    REVIEW_SUPPRESSED_BY_RECOVERY,
                    INFO,
                    "Обычная карточка временно скрыта из рабочего плана открытым восстановлением заказа"
            ));
        }
        if (row.botId() == null) {
            warnings.add(warning(REVIEW_BOT_MISSING, WARNING, "Карточке не назначен аккаунт"));
        } else if (row.botId() == STUB_BOT_ID) {
            warnings.add(warning(REVIEW_BOT_STUB, INFO, "Карточке назначен служебный аккаунт-заглушка"));
        } else if (Boolean.FALSE.equals(row.botActive())) {
            warnings.add(warning(REVIEW_BOT_INACTIVE, WARNING, "Карточке назначен неактивный аккаунт"));
        }
        if (row.botId() != null
                && row.botId() != STUB_BOT_ID
                && row.botOwnerWorkerId() != null
                && !Objects.equals(row.botOwnerWorkerId(), data.sourceWorkerId())) {
            warnings.add(warning(
                    REVIEW_BOT_OWNER_MISMATCH,
                    INFO,
                    "Аккаунт карточки закреплён за другим специалистом в общем городском пуле"
            ));
        }
        if (value.duplicatedBot) {
            warnings.add(warning(
                    REVIEW_BOT_DUPLICATED,
                    ERROR,
                    "Один аккаунт назначен нескольким неопубликованным карточкам в этом графе"
            ));
        }
        if (row.accountWalkDelayBotId() != null
                && !Objects.equals(row.accountWalkDelayBotId(), row.botId())) {
            warnings.add(warning(
                    REVIEW_DELAY_BOT_MISMATCH,
                    WARNING,
                    "Отложенный выгул рассчитан для другого аккаунта"
            ));
        }
        if (value.externalCounts.attentionCount() > 0) {
            warnings.add(warning(
                    EXTERNAL_CHECK_REQUIRES_ATTENTION,
                    WARNING,
                    "Внешние проверки требуют внимания: " + value.externalCounts.attentionCount()
            ));
        }

        return new ReviewNode(
                row.reviewId(),
                row.orderId(),
                row.workerId(),
                row.botId(),
                row.botActive(),
                row.botOwnerWorkerId(),
                row.publicationDate(),
                nagul ? NAGUL : PUBLISH,
                dueOnDate,
                futureWithinLookahead,
                outsideLookahead,
                row.textReady(),
                value.suppressedByRecovery,
                row.orderWaitingForClient(),
                row.activeBotReviewCount(),
                row.accountWalkDelayBotId(),
                value.activePerformerCount,
                value.externalCounts.activeCount(),
                value.externalCounts.attentionCount(),
                warnings
        );
    }

    private static RecoveryTaskNode buildRecovery(
            WorkloadTransferGraphData data,
            RecoveryRow row,
            boolean detached
    ) {
        List<Warning> warnings = new ArrayList<>();
        if (!Objects.equals(row.workerId(), data.sourceWorkerId())) {
            warnings.add(warning(
                    RECOVERY_WORKER_MISMATCH,
                    ERROR,
                    "Исполнитель восстановления не совпадает с исполнителем активного заказа"
            ));
        }
        if (detached
                && !row.archivedSource()
                && row.orderWorkerId() != null
                && !Objects.equals(row.orderWorkerId(), data.sourceWorkerId())) {
            warnings.add(warning(
                    RECOVERY_ORDER_NOT_OWNED_BY_SOURCE,
                    ERROR,
                    "Восстановление назначено специалисту, но заказ принадлежит другому специалисту"
            ));
        }
        if (!Objects.equals(row.batchManagerId(), data.managerId())
                || (row.taskManagerId() != null && !Objects.equals(row.taskManagerId(), data.managerId()))) {
            warnings.add(warning(
                    RECOVERY_MANAGER_MISMATCH,
                    ERROR,
                    "Менеджер восстановления не совпадает с выбранным менеджером"
            ));
        }
        if (row.archivedSource()) {
            warnings.add(warning(
                    ARCHIVED_RECOVERY_SOURCE,
                    INFO,
                    "Активное восстановление относится к уже архивированному заказу"
            ));
        } else if (detached
                && row.orderComplete()
                && Objects.equals(row.orderWorkerId(), data.sourceWorkerId())) {
            warnings.add(warning(
                    COMPLETED_RECOVERY_SOURCE,
                    INFO,
                    "Активное восстановление относится к завершённому заказу того же специалиста"
            ));
        }
        if (row.botId() == null) {
            warnings.add(warning(RECOVERY_BOT_MISSING, WARNING, "Восстановлению не назначен аккаунт"));
        } else if (Boolean.FALSE.equals(row.botActive())) {
            warnings.add(warning(RECOVERY_BOT_INACTIVE, WARNING, "Восстановлению назначен неактивный аккаунт"));
        }
        return new RecoveryTaskNode(
                row.taskId(),
                row.orderId(),
                row.archiveCompanyId(),
                row.workerId(),
                row.taskManagerId(),
                row.batchManagerId(),
                row.botId(),
                row.botActive(),
                row.scheduledDate(),
                isDue(row.scheduledDate(), data.date()),
                row.archivedSource(),
                warnings
        );
    }

    private static BadTaskNode buildBad(
            WorkloadTransferGraphData data,
            BadRow row,
            boolean detached
    ) {
        List<Warning> warnings = new ArrayList<>();
        if (!Objects.equals(row.workerId(), data.sourceWorkerId())) {
            warnings.add(warning(
                    BAD_WORKER_MISMATCH,
                    ERROR,
                    "Исполнитель задачи «Плохие» не совпадает с исполнителем активного заказа"
            ));
        }
        if (detached) {
            warnings.add(warning(
                    BAD_ORDER_NOT_OWNED_BY_SOURCE,
                    ERROR,
                    "Задача «Плохие» назначена специалисту, но активный заказ принадлежит другому специалисту"
            ));
        }
        if (row.botId() == null) {
            warnings.add(warning(BAD_BOT_MISSING, WARNING, "Задаче «Плохие» не назначен аккаунт"));
        } else if (Boolean.FALSE.equals(row.botActive())) {
            warnings.add(warning(BAD_BOT_INACTIVE, WARNING, "Задаче «Плохие» назначен неактивный аккаунт"));
        }
        return new BadTaskNode(
                row.taskId(),
                row.orderId(),
                row.sourceReviewId(),
                row.workerId(),
                row.botId(),
                row.botActive(),
                row.scheduledDate(),
                isDue(row.scheduledDate(), data.date()),
                warnings
        );
    }

    private static boolean isDue(LocalDate scheduledDate, LocalDate date) {
        return scheduledDate != null && !scheduledDate.isAfter(date);
    }

    private static boolean isStatus(String actual, String expected) {
        return actual != null && expected.equals(actual.trim());
    }

    private static Warning warning(
            WorkloadTransferCompanyGraph.WarningCode code,
            WorkloadTransferCompanyGraph.WarningSeverity severity,
            String message
    ) {
        return new Warning(code, severity, message);
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(value, 0);
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long safeMultiply(long value, int multiplier) {
        if (value <= 0 || multiplier <= 0) {
            return 0;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static final class CompanyAccumulator {

        private final CompanyRow row;
        private final Set<Long> linkedWorkerIds = new LinkedHashSet<>();
        private final List<OrderAccumulator> orders = new ArrayList<>();
        private final List<ReviewEnvelope> detachedReviews = new ArrayList<>();
        private final List<RecoveryRow> detachedRecoveryTasks = new ArrayList<>();
        private final List<BadRow> detachedBadTasks = new ArrayList<>();
        private long otherWorkerActiveOrderCount;
        private long unassignedActiveOrderCount;

        private CompanyAccumulator(CompanyRow row) {
            this.row = row;
        }
    }

    private static final class OrderAccumulator {

        private final OrderRow row;
        private final List<DetailRow> details = new ArrayList<>();
        private final List<ReviewEnvelope> reviews = new ArrayList<>();
        private final List<RecoveryRow> recoveryTasks = new ArrayList<>();
        private final List<BadRow> badTasks = new ArrayList<>();

        private OrderAccumulator(OrderRow row) {
            this.row = row;
        }
    }

    private record ReviewEnvelope(
            ReviewRow row,
            long activePerformerCount,
            ExternalCheckCountRow externalCounts,
            boolean suppressedByRecovery,
            boolean duplicatedBot
    ) {
    }

    private static final class TotalsCounter {

        private final EstimateRates rates;
        private long activeOrderCount;
        private long unpublishedReviewCount;
        private long newUnits;
        private long correctionUnits;
        private long nagulUnits;
        private long futureNagulUnits;
        private long nagulOutsideLookaheadUnits;
        private long publishUnits;
        private long futurePublishUnits;
        private long recoveryUnits;
        private long badUnits;
        private long activePerformerAssignmentCount;
        private long activeExternalCheckCount;
        private long attentionExternalCheckCount;

        private TotalsCounter(EstimateRates rates) {
            this.rates = rates;
        }

        private void add(WorkloadTotals value) {
            activeOrderCount = safeAdd(activeOrderCount, value.activeOrderCount());
            unpublishedReviewCount = safeAdd(unpublishedReviewCount, value.unpublishedReviewCount());
            newUnits = safeAdd(newUnits, value.newUnits());
            correctionUnits = safeAdd(correctionUnits, value.correctionUnits());
            nagulUnits = safeAdd(nagulUnits, value.nagulUnits());
            futureNagulUnits = safeAdd(futureNagulUnits, value.futureNagulUnits());
            nagulOutsideLookaheadUnits = safeAdd(
                    nagulOutsideLookaheadUnits,
                    value.nagulOutsideLookaheadUnits()
            );
            publishUnits = safeAdd(publishUnits, value.publishUnits());
            futurePublishUnits = safeAdd(futurePublishUnits, value.futurePublishUnits());
            recoveryUnits = safeAdd(recoveryUnits, value.recoveryUnits());
            badUnits = safeAdd(badUnits, value.badUnits());
            activePerformerAssignmentCount = safeAdd(
                    activePerformerAssignmentCount,
                    value.activePerformerAssignmentCount()
            );
            activeExternalCheckCount = safeAdd(activeExternalCheckCount, value.activeExternalCheckCount());
            attentionExternalCheckCount = safeAdd(
                    attentionExternalCheckCount,
                    value.attentionExternalCheckCount()
            );
        }

        private void addReview(ReviewNode value) {
            unpublishedReviewCount = safeAdd(unpublishedReviewCount, 1);
            if (value.stage() == NAGUL) {
                if (value.outsideNagulLookahead()) {
                    nagulOutsideLookaheadUnits = safeAdd(nagulOutsideLookaheadUnits, 1);
                } else {
                    nagulUnits = safeAdd(nagulUnits, 1);
                    if (value.futureWithinNagulLookahead()) {
                        futureNagulUnits = safeAdd(futureNagulUnits, 1);
                    }
                }
            } else {
                publishUnits = safeAdd(publishUnits, 1);
                if (!value.dueOnDate()) {
                    futurePublishUnits = safeAdd(futurePublishUnits, 1);
                }
            }
            activePerformerAssignmentCount = safeAdd(
                    activePerformerAssignmentCount,
                    value.activePerformerAssignmentCount()
            );
            activeExternalCheckCount = safeAdd(activeExternalCheckCount, value.activeExternalCheckCount());
            attentionExternalCheckCount = safeAdd(
                    attentionExternalCheckCount,
                    value.attentionExternalCheckCount()
            );
        }

        private void addRecovery() {
            recoveryUnits = safeAdd(recoveryUnits, 1);
        }

        private void addBad() {
            badUnits = safeAdd(badUnits, 1);
        }

        private WorkloadTotals toValue() {
            long estimatedMinutes = 0;
            estimatedMinutes = safeAdd(estimatedMinutes, safeMultiply(newUnits, rates.newMinutesPerCard()));
            estimatedMinutes = safeAdd(
                    estimatedMinutes,
                    safeMultiply(correctionUnits, rates.correctionMinutesPerOrder())
            );
            estimatedMinutes = safeAdd(estimatedMinutes, safeMultiply(nagulUnits, rates.walkMinutesPerCard()));
            estimatedMinutes = safeAdd(estimatedMinutes, safeMultiply(publishUnits, rates.publishMinutesPerCard()));
            estimatedMinutes = safeAdd(
                    estimatedMinutes,
                    safeMultiply(recoveryUnits, rates.recoveryMinutesPerTask())
            );
            estimatedMinutes = safeAdd(estimatedMinutes, safeMultiply(badUnits, rates.badMinutesPerTask()));
            return new WorkloadTotals(
                    activeOrderCount,
                    unpublishedReviewCount,
                    newUnits,
                    correctionUnits,
                    nagulUnits,
                    futureNagulUnits,
                    nagulOutsideLookaheadUnits,
                    publishUnits,
                    futurePublishUnits,
                    recoveryUnits,
                    badUnits,
                    activePerformerAssignmentCount,
                    activeExternalCheckCount,
                    attentionExternalCheckCount,
                    estimatedMinutes
            );
        }
    }
}
