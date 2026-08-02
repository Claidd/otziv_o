package com.hunt.otziv.workload_shadow.transfer.service;

import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferActionableWorkload;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferGraphDiagnostics;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.ARCHIVED_RECOVERY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.BAD_ORDER_NOT_OWNED_BY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.COMPANY_MANAGER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.COMPLETED_RECOVERY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.OTHER_WORKER_ACTIVE_ORDERS;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.RECOVERY_ORDER_NOT_OWNED_BY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.RECOVERY_WORKER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.REVIEW_BOT_DUPLICATED;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.REVIEW_BOT_MISSING;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.REVIEW_BOT_OWNER_MISMATCH;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.REVIEW_ORDER_NOT_OWNED_BY_SOURCE;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.SHARED_COMPANY_OWNERSHIP;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.SOURCE_COMPANY_LINK_MISSING;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode.UNASSIGNED_ACTIVE_ORDERS;
import static com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningSeverity.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.Warning;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.BadRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.CompanyOrderOwnershipRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.CompanyRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.CompanyWorkerLinkRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.DetailRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.EstimateRates;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.ExternalCheckCountRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.OrderRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.PerformerCountRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.RecoveryRow;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphData.ReviewRow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadTransferGraphAssemblerTest {

    private static final long SOURCE_WORKER_ID = 9L;
    private static final long MANAGER_ID = 7L;
    private static final LocalDate DATE = LocalDate.of(2026, 7, 27);
    private static final LocalDate LOOKAHEAD = DATE.plusDays(14);
    private static final EstimateRates RATES = new EstimateRates(5, 10, 4, 3, 10, 10);

    @Test
    void preservesCompanyWithMismatchedManagerAsDiagnosticError() {
        WorkloadTransferGraphData data = data(
                List.of(new CompanyRow(1L, "Компания", true, "В работе", 999L)),
                List.of(new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph =
                WorkloadTransferGraphAssembler.assemble(data).getFirst();

        assertTrue(hasWarning(graph.warnings(), COMPANY_MANAGER_MISMATCH));
    }

    @Test
    void newUsesOnlyPendingCardsAndCorrectionIsOnePerOrder() {
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID)),
                List.of(),
                List.of(
                        order(10L, "Новый", 50),
                        order(11L, "Новый", 50),
                        order(12L, "Коррекция", 80)
                ),
                List.of(
                        new DetailRow(10L, 20, 0, 0),
                        new DetailRow(10L, 30, 0, 0),
                        new DetailRow(11L, 50, 5, 2),
                        new DetailRow(12L, 80, 80, 0)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph = WorkloadTransferGraphAssembler.assemble(data).getFirst();
        assertEquals(0, findOrder(graph, 10L).newUnits());
        assertEquals(2, findOrder(graph, 11L).newUnits());
        assertEquals(0, findOrder(graph, 12L).newUnits());
        assertEquals(1, findOrder(graph, 12L).correctionUnits());
        assertEquals(2, graph.totals().newUnits());
        assertEquals(1, graph.totals().correctionUnits());
        assertEquals(20, graph.totals().estimatedMinutes());
    }

    @Test
    void botOwnerMismatchFromSharedCityPoolIsInformationalAndNotAGraphError() {
        ReviewRow sharedPoolReview = new ReviewRow(
                101L,
                10L,
                1L,
                SOURCE_WORKER_ID,
                501L,
                true,
                99L,
                DATE.plusDays(1),
                false,
                true,
                false,
                1,
                null
        );
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID)),
                List.of(new CompanyOrderOwnershipRow(1L, SOURCE_WORKER_ID, 1)),
                List.of(order(10L, "Новый", 1)),
                List.of(new DetailRow(10L, 1, 1, 0)),
                List.of(sharedPoolReview),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph =
                WorkloadTransferGraphAssembler.assemble(data).getFirst();
        Warning warning = findWarning(
                graph.orders().getFirst().reviews().getFirst().warnings(),
                REVIEW_BOT_OWNER_MISMATCH
        );
        WorkloadTransferGraphDiagnostics diagnostics =
                WorkloadTransferGraphDiagnostics.from(graph);

        assertEquals(INFO, warning.severity());
        assertEquals(0, diagnostics.errorCount());
        assertEquals(0, diagnostics.warningCount());
        assertFalse(diagnostics.hasReportableIssues());
    }

    @Test
    void historicalSharedCompanyLinkWithoutOtherActiveOrdersIsInformational() {
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(
                        new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID),
                        new CompanyWorkerLinkRow(1L, 99L)
                ),
                List.of(new CompanyOrderOwnershipRow(1L, SOURCE_WORKER_ID, 1)),
                List.of(order(10L, "Новый", 1)),
                List.of(new DetailRow(10L, 1, 0, 1)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph =
                WorkloadTransferGraphAssembler.assemble(data).getFirst();
        Warning warning = findWarning(graph.warnings(), SHARED_COMPANY_OWNERSHIP);
        WorkloadTransferGraphDiagnostics diagnostics =
                WorkloadTransferGraphDiagnostics.from(graph);

        assertTrue(graph.sharedOwnership());
        assertEquals(0, graph.otherWorkerActiveOrderCount());
        assertEquals(INFO, warning.severity());
        assertEquals(0, diagnostics.errorCount());
        assertEquals(0, diagnostics.warningCount());
        assertFalse(diagnostics.hasReportableIssues());
    }

    @Test
    void sharedCompanyWithOtherWorkersOrdersIsInformationalForSourceOrderBundle() {
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(
                        new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID),
                        new CompanyWorkerLinkRow(1L, 99L)
                ),
                List.of(
                        new CompanyOrderOwnershipRow(1L, SOURCE_WORKER_ID, 1),
                        new CompanyOrderOwnershipRow(1L, 99L, 2)
                ),
                List.of(order(10L, "Новый", 1)),
                List.of(new DetailRow(10L, 1, 1, 1)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph =
                WorkloadTransferGraphAssembler.assemble(data).getFirst();
        WorkloadTransferGraphDiagnostics diagnostics =
                WorkloadTransferGraphDiagnostics.from(graph);

        assertEquals(INFO, findWarning(graph.warnings(), SHARED_COMPANY_OWNERSHIP).severity());
        assertEquals(INFO, findWarning(graph.warnings(), OTHER_WORKER_ACTIVE_ORDERS).severity());
        assertEquals(2, graph.otherWorkerActiveOrderCount());
        assertFalse(diagnostics.hasReportableIssues());
    }

    @Test
    void completedOrderRecoveryOwnedBySameWorkerIsNotReportedAsOwnershipError() {
        RecoveryRow recovery = new RecoveryRow(
                201L,
                10L,
                1L,
                null,
                SOURCE_WORKER_ID,
                MANAGER_ID,
                MANAGER_ID,
                4L,
                true,
                DATE,
                false,
                SOURCE_WORKER_ID,
                true
        );
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(recovery),
                List.of(),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph =
                WorkloadTransferGraphAssembler.assemble(data).getFirst();

        assertTrue(hasWarning(graph.detachedRecoveryTasks().getFirst().warnings(), COMPLETED_RECOVERY_SOURCE));
        assertFalse(hasWarning(
                graph.detachedRecoveryTasks().getFirst().warnings(),
                RECOVERY_ORDER_NOT_OWNED_BY_SOURCE
        ));
        assertEquals(0, WorkloadTransferGraphDiagnostics.from(graph).errorCount());
    }

    @Test
    void oneOrderKeepsEveryActiveStageAndUsesConfiguredEstimateRates() {
        ReviewRow nagul = new ReviewRow(
                101L,
                10L,
                1L,
                SOURCE_WORKER_ID,
                2L,
                true,
                99L,
                DATE.plusDays(3),
                false,
                true,
                false,
                2,
                2L
        );
        ReviewRow publish = new ReviewRow(
                102L,
                10L,
                1L,
                SOURCE_WORKER_ID,
                3L,
                true,
                SOURCE_WORKER_ID,
                DATE.plusDays(5),
                true,
                true,
                false,
                1,
                null
        );
        RecoveryRow recovery = new RecoveryRow(
                201L,
                10L,
                1L,
                null,
                99L,
                MANAGER_ID,
                MANAGER_ID,
                4L,
                true,
                DATE.plusDays(1),
                false
        );
        BadRow bad = new BadRow(
                301L,
                10L,
                1L,
                101L,
                SOURCE_WORKER_ID,
                5L,
                true,
                DATE
        );
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(
                        new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID),
                        new CompanyWorkerLinkRow(1L, 99L)
                ),
                List.of(),
                List.of(order(10L, "Новый", 2)),
                List.of(new DetailRow(10L, 2, 2, 0)),
                List.of(nagul, publish),
                List.of(recovery),
                List.of(bad),
                List.of(new PerformerCountRow(101L, 1)),
                List.of(new ExternalCheckCountRow(102L, 1, 2))
        );

        WorkloadTransferCompanyGraph graph = WorkloadTransferGraphAssembler.assemble(data).getFirst();
        OrderNode order = graph.orders().getFirst();

        assertTrue(graph.sharedOwnership());
        assertTrue(hasWarning(graph.warnings(), SHARED_COMPANY_OWNERSHIP));
        assertEquals(0, order.newUnits());
        assertEquals(1, order.totals().nagulUnits());
        assertEquals(1, order.totals().futureNagulUnits());
        assertEquals(1, order.totals().publishUnits());
        assertEquals(1, order.totals().futurePublishUnits());
        assertEquals(1, order.totals().recoveryUnits());
        assertEquals(1, order.totals().badUnits());
        assertEquals(1, order.totals().activePerformerAssignmentCount());
        assertEquals(1, order.totals().activeExternalCheckCount());
        assertEquals(2, order.totals().attentionExternalCheckCount());
        assertEquals(27, order.totals().estimatedMinutes());
        assertTrue(order.reviews().stream().allMatch(value -> value.suppressedByOpenRecovery()));
        assertTrue(hasWarning(order.reviews().getFirst().warnings(), REVIEW_BOT_DUPLICATED));
        assertTrue(hasWarning(order.reviews().getFirst().warnings(), REVIEW_BOT_OWNER_MISMATCH));
        assertTrue(hasWarning(order.recoveryTasks().getFirst().warnings(), RECOVERY_WORKER_MISMATCH));
    }

    @Test
    void reviewWithoutAssignedBotIsInformationalAndDoesNotDegradeTransferGraph() {
        ReviewRow reviewWithoutBot = new ReviewRow(
                101L,
                10L,
                1L,
                SOURCE_WORKER_ID,
                null,
                null,
                null,
                DATE,
                false,
                true,
                false,
                0,
                null
        );
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID)),
                List.of(),
                List.of(order(10L, "Новый", 1)),
                List.of(new DetailRow(10L, 1, 1, 0)),
                List.of(reviewWithoutBot),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph =
                WorkloadTransferGraphAssembler.assemble(data).getFirst();
        WorkloadTransferCompanyGraph.Warning warning = graph.orders().getFirst()
                .reviews().getFirst().warnings().stream()
                .filter(value -> value.code() == REVIEW_BOT_MISSING)
                .findFirst()
                .orElseThrow();
        WorkloadTransferGraphDiagnostics diagnostics =
                WorkloadTransferGraphDiagnostics.from(graph);

        assertEquals(WorkloadTransferCompanyGraph.WarningSeverity.INFO, warning.severity());
        assertEquals(0, diagnostics.warningCount());
        assertEquals(0, diagnostics.errorCount());
    }

    @Test
    void actionableWorkloadExcludesWaitingFutureUnreadyAndSuppressedNodes() {
        OrderRow waitingCorrection = new OrderRow(
                11L,
                1L,
                "Коррекция",
                SOURCE_WORKER_ID,
                MANAGER_ID,
                true,
                false,
                DATE,
                DATE,
                1
        );
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(new CompanyWorkerLinkRow(1L, SOURCE_WORKER_ID)),
                List.of(),
                List.of(
                        order(10L, "Новый", 6),
                        waitingCorrection,
                        order(12L, "Коррекция", 1)
                ),
                List.of(
                        new DetailRow(10L, 6, 6, 2),
                        new DetailRow(11L, 1, 1, 0),
                        new DetailRow(12L, 1, 1, 0)
                ),
                List.of(
                        review(101L, 10L, DATE.plusDays(3), false, true),
                        review(102L, 10L, DATE.plusDays(3), false, false),
                        review(103L, 10L, LOOKAHEAD.plusDays(1), false, true),
                        review(104L, 10L, DATE, true, true),
                        review(105L, 10L, DATE.plusDays(1), true, true),
                        review(106L, 10L, DATE, true, false),
                        review(107L, 11L, DATE, true, true),
                        review(108L, 12L, DATE.plusDays(2), false, true),
                        review(109L, 88L, DATE.plusDays(2), false, true),
                        new ReviewRow(
                                110L,
                                90L,
                                1L,
                                SOURCE_WORKER_ID,
                                1110L,
                                true,
                                SOURCE_WORKER_ID,
                                DATE.plusDays(2),
                                false,
                                true,
                                true,
                                1,
                                null
                        )
                ),
                List.of(
                        recovery(201L, 11L, DATE),
                        recovery(202L, 12L, DATE.plusDays(1)),
                        new RecoveryRow(
                                203L,
                                null,
                                1L,
                                1L,
                                SOURCE_WORKER_ID,
                                MANAGER_ID,
                                MANAGER_ID,
                                9L,
                                true,
                                DATE,
                                true
                        )
                ),
                List.of(
                        bad(301L, 11L, DATE),
                        bad(302L, 12L, DATE.plusDays(1)),
                        bad(303L, 89L, DATE)
                ),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph = WorkloadTransferGraphAssembler.assemble(data).getFirst();
        WorkloadTransferActionableWorkload actionable = WorkloadTransferActionableWorkload.calculate(
                graph,
                new WorkloadTransferActionableWorkload.EstimateRates(5, 10, 4, 3, 10, 10)
        );

        assertEquals(2, actionable.newUnits());
        assertEquals(1, actionable.correctionUnits());
        assertEquals(2, actionable.nagulUnits());
        assertEquals(1, actionable.publishUnits());
        assertEquals(1, actionable.recoveryUnits());
        assertEquals(1, actionable.badUnits());
        assertEquals(8, actionable.problemUnits());
        assertEquals(51, actionable.estimatedMinutes());
    }

    @Test
    void preservesDetachedAndArchivedResponsibilitiesInsteadOfHidingThem() {
        ReviewRow detachedReview = new ReviewRow(
                101L,
                88L,
                1L,
                SOURCE_WORKER_ID,
                null,
                null,
                null,
                LOOKAHEAD.plusDays(1),
                false,
                false,
                false,
                1,
                null
        );
        RecoveryRow archivedRecovery = new RecoveryRow(
                201L,
                null,
                1L,
                1L,
                SOURCE_WORKER_ID,
                MANAGER_ID,
                MANAGER_ID,
                null,
                null,
                DATE,
                true
        );
        BadRow detachedBad = new BadRow(
                301L,
                89L,
                1L,
                101L,
                SOURCE_WORKER_ID,
                null,
                null,
                DATE
        );
        WorkloadTransferGraphData data = data(
                List.of(company()),
                List.of(),
                List.of(
                        new CompanyOrderOwnershipRow(1L, 99L, 2),
                        new CompanyOrderOwnershipRow(1L, null, 1)
                ),
                List.of(),
                List.of(),
                List.of(detachedReview),
                List.of(archivedRecovery),
                List.of(detachedBad),
                List.of(),
                List.of()
        );

        WorkloadTransferCompanyGraph graph = WorkloadTransferGraphAssembler.assemble(data).getFirst();

        assertFalse(graph.sourceCompanyLinkPresent());
        assertEquals(2, graph.otherWorkerActiveOrderCount());
        assertEquals(1, graph.unassignedActiveOrderCount());
        assertTrue(hasWarning(graph.warnings(), SOURCE_COMPANY_LINK_MISSING));
        assertTrue(hasWarning(graph.warnings(), OTHER_WORKER_ACTIVE_ORDERS));
        assertTrue(hasWarning(graph.warnings(), UNASSIGNED_ACTIVE_ORDERS));
        assertTrue(hasWarning(graph.detachedReviews().getFirst().warnings(), REVIEW_ORDER_NOT_OWNED_BY_SOURCE));
        assertTrue(hasWarning(graph.detachedReviews().getFirst().warnings(), REVIEW_BOT_MISSING));
        assertTrue(hasWarning(graph.detachedRecoveryTasks().getFirst().warnings(), ARCHIVED_RECOVERY_SOURCE));
        assertTrue(hasWarning(graph.detachedBadTasks().getFirst().warnings(), BAD_ORDER_NOT_OWNED_BY_SOURCE));
        assertEquals(1, graph.totals().nagulOutsideLookaheadUnits());
        assertEquals(1, graph.totals().recoveryUnits());
        assertEquals(1, graph.totals().badUnits());
        assertEquals(20, graph.totals().estimatedMinutes());

        WorkloadTransferGraphDiagnostics diagnostics = WorkloadTransferGraphDiagnostics.from(graph);
        assertEquals(3, diagnostics.warningCount());
        assertEquals(3, diagnostics.errorCount());
        assertFalse(diagnostics.warningCodes().contains(OTHER_WORKER_ACTIVE_ORDERS));
        assertTrue(diagnostics.errorCodes().contains(SOURCE_COMPANY_LINK_MISSING));
        assertTrue(diagnostics.errorCodes().contains(REVIEW_ORDER_NOT_OWNED_BY_SOURCE));
        assertEquals(
                diagnostics.warningCodes().stream().map(Enum::name).sorted().toList(),
                List.of(diagnostics.compactWarningCodes().split(","))
        );
    }

    private static WorkloadTransferGraphData data(
            List<CompanyRow> companies,
            List<CompanyWorkerLinkRow> links,
            List<CompanyOrderOwnershipRow> ownership,
            List<OrderRow> orders,
            List<DetailRow> details,
            List<ReviewRow> reviews,
            List<RecoveryRow> recovery,
            List<BadRow> bad,
            List<PerformerCountRow> performer,
            List<ExternalCheckCountRow> external
    ) {
        return new WorkloadTransferGraphData(
                SOURCE_WORKER_ID,
                MANAGER_ID,
                DATE,
                LOOKAHEAD,
                RATES,
                companies,
                links,
                ownership,
                orders,
                details,
                reviews,
                recovery,
                bad,
                performer,
                external
        );
    }

    private static CompanyRow company() {
        return new CompanyRow(1L, "Компания", true, "В работе", MANAGER_ID);
    }

    private static OrderRow order(long orderId, String status, int declaredUnits) {
        return new OrderRow(
                orderId,
                1L,
                status,
                SOURCE_WORKER_ID,
                MANAGER_ID,
                false,
                false,
                DATE,
                DATE,
                declaredUnits
        );
    }

    private static ReviewRow review(
            long reviewId,
            long orderId,
            LocalDate publicationDate,
            boolean walked,
            boolean textReady
    ) {
        return new ReviewRow(
                reviewId,
                orderId,
                1L,
                SOURCE_WORKER_ID,
                reviewId + 1000,
                true,
                SOURCE_WORKER_ID,
                publicationDate,
                walked,
                textReady,
                false,
                1,
                null
        );
    }

    private static RecoveryRow recovery(long taskId, long orderId, LocalDate scheduledDate) {
        return new RecoveryRow(
                taskId,
                orderId,
                1L,
                null,
                SOURCE_WORKER_ID,
                MANAGER_ID,
                MANAGER_ID,
                taskId + 1000,
                true,
                scheduledDate,
                false
        );
    }

    private static BadRow bad(long taskId, long orderId, LocalDate scheduledDate) {
        return new BadRow(
                taskId,
                orderId,
                1L,
                null,
                SOURCE_WORKER_ID,
                taskId + 1000,
                true,
                scheduledDate
        );
    }

    private static OrderNode findOrder(WorkloadTransferCompanyGraph graph, long orderId) {
        return graph.orders().stream()
                .filter(value -> value.orderId() == orderId)
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasWarning(
            List<Warning> warnings,
            WorkloadTransferCompanyGraph.WarningCode code
    ) {
        return warnings.stream().anyMatch(value -> value.code() == code);
    }

    private static Warning findWarning(
            List<Warning> warnings,
            WorkloadTransferCompanyGraph.WarningCode code
    ) {
        return warnings.stream()
                .filter(value -> value.code() == code)
                .findFirst()
                .orElseThrow();
    }
}
