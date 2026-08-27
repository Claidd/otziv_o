package com.hunt.otziv.workload_shadow.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.workload_shadow.service.WorkloadShadowTransferSimulationService;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphQueryService;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadTransferRepositoryContractTest {

    @Test
    void everyDeclaredRuntimeRepositoryMethodHasQueryAnnotation() {
        assertQueryOnly(WorkloadTransferGraphRepository.class);
        assertQueryOnly(WorkloadShadowTransferRepository.class);
        assertQueryOnly(WorkloadTransferWorkflowRepository.class);
        assertQueryOnly(WorkloadTransferOfferRepository.class);
        assertQueryOnly(WorkloadTransferExecutionRepository.class);
        assertQueryOnly(WorkloadTransferMaintenanceRepository.class);
        assertQueryOnly(WorkloadEmergencyAssignmentRepository.class);
    }

    @Test
    void companyTransferCandidatesAreAlwaysLimitedToTheSourceManager() {
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "findRecommendationCandidates",
                "candidate_current.manager_id = transfer_case.manager_id"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "findStageCandidates",
                "candidate_current.manager_id = workflow.manager_id"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertOffer",
                "candidate_current.manager_id = workflow.manager_id"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertEligibleOfferBatch",
                "candidate_current.manager_id = workflow.manager_id"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "findClaimedContext",
                "candidate_current.manager_id = workflow.manager_id"
        );
    }

    @Test
    void liveWorkflowStagingUsesCurrentFailureThresholdAndSourceQuota() {
        assertQueryContains(
                WorkloadShadowTransferRepository.class,
                "findSourceWorkers",
                "current.last_day_reached_100 = FALSE"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "findRecommendationCandidates",
                "transfer_case.failure_number > :allowedFailureDays"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "findRecommendationCandidates",
                "source_current.failure_days > :allowedFailureDays"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "findRecommendationCandidates",
                "source_current.last_day_reached_100 = FALSE"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "reservedBySourceWorkerSince",
                "workflow.source_worker_id AS source_worker_id"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "reservedBySourceWorkerSince",
                "emergency.source_worker_id AS source_worker_id"
        );
    }
    @Test
    void workflowAndCandidateStagingUseBoundedBulkJsonQueries() {
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowsBulk",
                "INSERT IGNORE INTO workload_transfer_workflows"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowCandidatesBulk",
                "COLLATE utf8mb4_unicode_ci"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "countIncompleteWorkflowQueues",
                "COLLATE utf8mb4_unicode_ci"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowsBulk",
                "FROM JSON_TABLE("
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowsBulk",
                "transfer_case.manager_id = workflow_row.manager_id"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowsBulk",
                "transfer_case.source_worker_id ="
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowsBulk",
                "transfer_case.company_id = workflow_row.company_id"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowCandidatesBulk",
                "INSERT IGNORE INTO workload_transfer_workflow_candidates"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowCandidatesBulk",
                "FROM JSON_TABLE("
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowCandidatesBulk",
                "workflow.manager_id = candidate_row.manager_id"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowCandidatesBulk",
                "candidate_current.manager_id = workflow.manager_id"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "countIncompleteWorkflowQueues",
                "expected_candidate_count"
        );
    }

    @Test
    void unavailableQueueHeadIsAtomicallySkippedWithoutBlockingNextCandidate() {
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "skipUnavailableWaitingCandidates",
                "SET candidate.status = 'UNAVAILABLE'"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "skipUnavailableWaitingCandidates",
                "candidate_current.manager_id = workflow.manager_id"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "skipUnavailableWaitingCandidates",
                "candidate_current.accepts_company_transfers <> TRUE"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "skipUnavailableWaitingCandidates",
                "candidate.response_reason = CASE"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "findStageCandidates",
                "earlier.status IN ('WAITING', 'OFFERED', 'ACCEPTED')"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "markExhaustedWorkflows",
                "candidate.status = 'WAITING'"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "releaseUnavailableUndeliveredOffers",
                "SET offer.status = 'CANCELLED'"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "releaseUnavailableUndeliveredOffers",
                "candidate.status = 'UNAVAILABLE'"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "releaseUnavailableUndeliveredOffers",
                "offer.processing_lease_until <= :now"
        );
    }

    @Test
    void offerStagingIsSetBasedBoundedAndConcurrentDuplicatesAreBenign() {
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertEligibleOfferBatch",
                "INSERT IGNORE INTO workload_transfer_offers"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertEligibleOfferBatch",
                "staging_batch_token"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertEligibleOfferBatch",
                ":stagingBatchToken"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertEligibleOfferBatch",
                "UUID()"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertEligibleOfferBatch",
                "LIMIT :rowLimit"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertEligibleOfferBatch",
                "JSON_TABLE("
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "markReadyOfferBatchOffered",
                "UPDATE workload_transfer_workflows workflow"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "markReadyOfferBatchOffered",
                ":allManagers = TRUE"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "markReadyOfferBatchOffered",
                ":managerIdsJson"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "markReadyOfferBatchOffered",
                "workflow.manager_id"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "markReadyOfferBatchOffered",
                "offer.staging_batch_token = :stagingBatchToken"
        );
        assertQueryDoesNotContain(
                WorkloadTransferOfferRepository.class,
                "markReadyOfferBatchOffered",
                "offer.created_at = :now"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "claimDueOffers",
                ":allManagers = TRUE"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "claimDueOffers",
                ":managerIdsJson"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "claimDueOffers",
                "allowed_workflow.manager_id"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "claimDueOffers",
                "allowed_workflow.active = TRUE"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "claimDueOffers",
                "allowed_workflow.status = 'OFFERED'"
        );
        assertQueryContains(
                WorkloadTransferWorkflowRepository.class,
                "insertWorkflowsBulk",
                "INSERT IGNORE INTO workload_transfer_workflows"
        );
    }

    @Test
    void lifecycleSelfHealingIsBoundedSetBasedAndClosesChildrenFirst() {
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "lockOrphanReadyOfferIds",
                "offer.status = 'READY'"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "lockOrphanReadyOfferIds",
                "workflow.status = 'READY_TO_OFFER'"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "lockOrphanReadyOfferIds",
                "workflow.current_offer_id IS NULL"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "lockOrphanReadyOfferIds",
                "LIMIT :rowLimit"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "lockOrphanReadyOfferIds",
                "FOR UPDATE"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "cancelOrphanReadyOffers",
                "last_error_code = 'ORPHAN_READY_OFFER'"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "lockExpiredWorkflowIds",
                "LIMIT :rowLimit"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "lockExpiredWorkflowIds",
                "FOR UPDATE"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "closeOffersForExpiredWorkflows",
                "status IN ('READY', 'RETRY', 'SENDING', 'OFFERED', 'ACCEPTED')"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "closeCandidatesForExpiredWorkflows",
                "status IN ('WAITING', 'OFFERED', 'ACCEPTED')"
        );
        assertQueryContains(
                WorkloadTransferMaintenanceRepository.class,
                "cancelExpiredWorkflows",
                "status = 'CANCELLED_EXPIRED'"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "closeAcceptedCandidateForBlockedWorkflow",
                "candidate.status = 'ACCEPTED'"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "closeAcceptedOfferForBlockedWorkflow",
                "offer.status = 'ACCEPTED'"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "blockWorkflow",
                "accepted_worker_id = NULL"
        );
    }

    @Test
    void singleRecipientFallbackForcesOnlyRealNoResponseAndKeepsOwnerVisible() {
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                "candidate.status IN ('DECLINED', 'EXPIRED')"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                "offer.status IN ('DECLINED', 'EXPIRED')"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                "AND open_candidate.status IN ("
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                "candidateCount"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                "unresolved_candidate.status NOT IN"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                "RAND()"
        );
        assertQueryDoesNotContain(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                ") = 1"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                "candidate_current.recipient_eligible = TRUE"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "lockSingleRecipientForcedTransfers",
                "FOR UPDATE"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "forceSingleRecipientAcceptedAfterNoResponse",
                "workflow.status = 'ACCEPTED'"
        );
        assertQueryDoesNotContain(
                WorkloadTransferOfferRepository.class,
                "forceSingleRecipientAcceptedAfterNoResponse",
                "FROM workload_transfer_workflows workflow"
        );
        assertQueryContains(
                WorkloadShadowEventRepository.class,
                "upsertSingleRecipientForcedTransferEvents",
                "LIVE_SINGLE_RECIPIENT_FORCED"
        );
        assertQueryContains(
                WorkloadShadowEventRepository.class,
                "upsertSingleRecipientForcedTransferEvents",
                "Нужен дополнительный получатель нагрузки"
        );
        assertQueryContains(
                WorkloadShadowEventRepository.class,
                "upsertExhaustedQueueForcedTransferEvents",
                "LIVE_EXHAUSTED_QUEUE_FORCED"
        );
        assertQueryContains(
                WorkloadShadowEventRepository.class,
                "upsertExhaustedQueueForcedTransferEvents",
                "Задачи без связанного заказа не передаются"
        );
    }

    @Test
    void employeeResponseDeadlineStartsOnlyAfterTelegramDelivery() {
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertOffer",
                "delivery_deadline_at"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertEligibleOfferBatch",
                "delivery_deadline_at"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "insertOffer",
                ":deliveryDeadlineAt"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "claimDueOffers",
                "delivery_deadline_at > :now"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "markDelivered",
                "expires_at = :expiresAt"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "expireUndeliveredOffers",
                "DELIVERY_DEADLINE_EXPIRED"
        );
        assertQueryContains(
                WorkloadTransferOfferRepository.class,
                "expireUndeliveredOffers",
                "workflow.status = 'READY_TO_OFFER'"
        );
    }

    @Test
    void activeWorkStagesDoNotBlockAnAtomicCompanyPackageTransfer() {
        assertQueryDoesNotContain(
                WorkloadTransferWorkflowRepository.class,
                "findRecommendationCandidates",
                "unsafe_review.review_vigul"
        );
        assertQueryDoesNotContain(
                WorkloadTransferWorkflowRepository.class,
                "findRecommendationCandidates",
                "unsafe_review.review_text_ready_at"
        );
        assertQueryDoesNotContain(
                WorkloadTransferExecutionRepository.class,
                "countFinanciallyUnsafeOrders",
                "unsafe_review.review_vigul"
        );
        assertQueryDoesNotContain(
                WorkloadTransferExecutionRepository.class,
                "countFinanciallyUnsafeOrders",
                "unsafe_review.review_text_ready_at"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "transferReviews",
                "COALESCE(review_publish, 0) = 0"
        );
    }

    @Test
    void financialGuardOnlyStopsOrdersAtSettlementBoundaryOrWithFinancialDocuments() {
        assertFinancialSettlementGuard(
                WorkloadTransferWorkflowRepository.class,
                "findRecommendationCandidates"
        );
        assertFinancialSettlementGuard(
                WorkloadTransferExecutionRepository.class,
                "countFinanciallyUnsafeOrders"
        );
    }

    @Test
    void executionLocksCompanyAndSourceOrdersBeforeAtomicTransfer() {
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockWorkerManagerAssignments",
                "linked_worker.worker_id IN (:workerIds)"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockWorkerManagerAssignments",
                "manager.manager_id AS managerId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockWorkerManagerAssignments",
                "FOR UPDATE"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockCompanyForTransfer",
                "FOR UPDATE"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockActiveSourceOrderIds",
                "orders.order_worker = :sourceWorkerId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockActiveSourceOrderIds",
                "orders.order_company = :companyId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockActiveSourceOrderIds",
                "FOR UPDATE"
        );
    }

    @Test
    void rollbackComparesMutableStateWithTheCapturedAuditBaseline() {
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockRollbackOrderIds",
                "ORDER BY orders.order_id"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "lockRollbackOrderIds",
                "FOR UPDATE"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "auditOrders",
                "'counter', COALESCE(orders.order_counter, 0)"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "auditReviews",
                "'walked', COALESCE(review.review_vigul, 0)"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "auditReviews",
                "'textReadyAt', COALESCE("
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "countRollbackUnsafeEntities",
                "JSON_EXTRACT("
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "countRollbackUnsafeEntities",
                "'$.counter'"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "countRollbackUnsafeEntities",
                "'$.walked'"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "rollbackOrders",
                "audit.execution_id = :executionId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "rollbackOrders",
                "'$.counter'"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "rollbackOrders",
                "NOT EXISTS ("
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "rollbackOrders",
                "frozen_allocation.status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "rollbackReviews",
                "audit.execution_id = :executionId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "rollbackReviews",
                "'$.walked'"
        );
        assertQueryDoesNotContain(
                WorkloadTransferExecutionRepository.class,
                "rollbackOrders",
                "COALESCE(order_counter, 0) = 0"
        );
        assertQueryDoesNotContain(
                WorkloadTransferExecutionRepository.class,
                "rollbackReviews",
                "COALESCE(review_vigul, 0) = 0"
        );
    }

    @Test
    void companyPackageUpdatesOnlySourceWorkersEntities() {
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "transferOrders",
                "order_worker = :sourceWorkerId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "transferOrders",
                "order_company = :companyId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "transferReviews",
                "review_worker = :sourceWorkerId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "transferBadTasks",
                "bad_review_task_worker = :sourceWorkerId"
        );
        assertQueryContains(
                WorkloadTransferExecutionRepository.class,
                "transferRecoveryTasks",
                "review_recovery_task_worker = :sourceWorkerId"
        );
    }

    @Test
    void emergencyFallbackIsOneCardAndNeverACompanyPackage() {
        assertQueryContains(
                WorkloadEmergencyAssignmentRepository.class,
                "findReadyCases",
                "source_current.last_day_reached_100 = FALSE"
        );
        assertQueryContains(
                WorkloadEmergencyAssignmentRepository.class,
                "insertPrepared",
                "source_current.last_day_reached_100 = FALSE"
        );
        assertQueryContains(
                WorkloadEmergencyAssignmentRepository.class,
                "transferReview",
                "UPDATE reviews review"
        );
        assertQueryContains(
                WorkloadEmergencyAssignmentRepository.class,
                "transferReview",
                "review.review_id = :reviewId"
        );
        assertQueryDoesNotContain(
                WorkloadEmergencyAssignmentRepository.class,
                "transferReview",
                "UPDATE orders"
        );
        assertQueryContains(
                WorkloadEmergencyAssignmentRepository.class,
                "insertPrepared",
                "prior_candidate.worker_id = :targetWorkerId"
        );
    }

    @Test
    void transferServicesDoNotOwnJdbcTemplates() {
        assertNoJdbcFields(WorkloadTransferGraphQueryService.class);
        assertNoJdbcFields(WorkloadShadowTransferSimulationService.class);
    }

    private void assertQueryOnly(Class<?> repositoryType) {
        for (Method method : repositoryType.getDeclaredMethods()) {
            assertTrue(
                    method.isAnnotationPresent(Query.class),
                    () -> repositoryType.getSimpleName() + "." + method.getName()
                            + " должен быть объявлен через @Query"
            );
        }
    }

    private void assertNoJdbcFields(Class<?> serviceType) {
        boolean hasJdbc = Arrays.stream(serviceType.getDeclaredFields())
                .map(field -> field.getType().getName())
                .anyMatch(name -> name.contains("JdbcTemplate"));
        assertFalse(hasJdbc, () -> serviceType.getSimpleName() + " не должен владеть JdbcTemplate");
    }

    private void assertFinancialSettlementGuard(
            Class<?> repositoryType,
            String methodName
    ) {
        assertQueryContains(
                repositoryType,
                methodName,
                "unsafe_order.order_counter"
        );
        assertQueryContains(
                repositoryType,
                methodName,
                "unsafe_order.order_amount"
        );
        assertQueryContains(
                repositoryType,
                methodName,
                "unsafe_order.order_pay_day IS NOT NULL"
        );
        assertQueryContains(
                repositoryType,
                methodName,
                "FROM zp unsafe_salary"
        );
        assertQueryContains(
                repositoryType,
                methodName,
                "FROM payment_check unsafe_check"
        );
    }

    private void assertQueryContains(
            Class<?> repositoryType,
            String methodName,
            String expectedFragment
    ) {
        Query query = query(repositoryType, methodName);
        assertTrue(
                query.value().contains(expectedFragment),
                () -> repositoryType.getSimpleName() + "." + methodName
                        + " должен содержать ограничение: " + expectedFragment
        );
    }

    private void assertQueryDoesNotContain(
            Class<?> repositoryType,
            String methodName,
            String unexpectedFragment
    ) {
        Query query = query(repositoryType, methodName);
        assertFalse(
                query.value().contains(unexpectedFragment),
                () -> repositoryType.getSimpleName() + "." + methodName
                        + " не должен содержать: " + unexpectedFragment
        );
    }

    private Query query(Class<?> repositoryType, String methodName) {
        Method method = Arrays.stream(repositoryType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        Query query = method.getAnnotation(Query.class);
        assertNotNull(
                query,
                () -> repositoryType.getSimpleName() + "." + methodName
                        + " должен быть объявлен через @Query"
        );
        return query;
    }
}

