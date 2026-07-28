package com.hunt.otziv.workload_shadow.transfer;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only snapshot of the source worker's active order bundle inside a company.
 *
 * <p>The snapshot deliberately contains mismatched and detached nodes. Hiding those nodes
 * would make the observation mode look healthy while an active task remains assigned to a
 * different specialist. Orders belonging to other specialists are not part of the bundle;
 * their aggregate counts are retained only as informational context.</p>
 */
public record WorkloadTransferCompanyGraph(
        long companyId,
        String companyTitle,
        boolean companyActive,
        String companyStatus,
        long managerId,
        boolean sourceCompanyLinkPresent,
        List<Long> linkedWorkerIds,
        boolean sharedOwnership,
        long otherWorkerActiveOrderCount,
        long unassignedActiveOrderCount,
        List<OrderNode> orders,
        List<ReviewNode> detachedReviews,
        List<RecoveryTaskNode> detachedRecoveryTasks,
        List<BadTaskNode> detachedBadTasks,
        WorkloadTotals totals,
        List<Warning> warnings
) {

    public WorkloadTransferCompanyGraph {
        linkedWorkerIds = List.copyOf(linkedWorkerIds);
        orders = List.copyOf(orders);
        detachedReviews = List.copyOf(detachedReviews);
        detachedRecoveryTasks = List.copyOf(detachedRecoveryTasks);
        detachedBadTasks = List.copyOf(detachedBadTasks);
        warnings = List.copyOf(warnings);
    }

    public record OrderNode(
            long orderId,
            String status,
            Long workerId,
            Long managerId,
            boolean waitingForClient,
            boolean clientTextExpected,
            LocalDate createdDate,
            LocalDate changedDate,
            int declaredOrderUnits,
            int declaredDetailUnits,
            int detailCount,
            int actualReviewCards,
            int newUnits,
            int correctionUnits,
            List<ReviewNode> reviews,
            List<RecoveryTaskNode> recoveryTasks,
            List<BadTaskNode> badTasks,
            WorkloadTotals totals,
            List<Warning> warnings
    ) {

        public OrderNode {
            reviews = List.copyOf(reviews);
            recoveryTasks = List.copyOf(recoveryTasks);
            badTasks = List.copyOf(badTasks);
            warnings = List.copyOf(warnings);
        }
    }

    public record ReviewNode(
            long reviewId,
            long orderId,
            Long workerId,
            Long botId,
            Boolean botActive,
            Long botOwnerWorkerId,
            LocalDate publicationDate,
            ReviewStage stage,
            boolean dueOnDate,
            boolean futureWithinNagulLookahead,
            boolean outsideNagulLookahead,
            boolean textReady,
            boolean suppressedByOpenRecovery,
            boolean orderWaitingForClient,
            long activeBotReviewCount,
            Long accountWalkDelayBotId,
            long activePerformerAssignmentCount,
            long activeExternalCheckCount,
            long attentionExternalCheckCount,
            List<Warning> warnings
    ) {

        public ReviewNode {
            warnings = List.copyOf(warnings);
        }
    }

    public record RecoveryTaskNode(
            long taskId,
            Long orderId,
            Long archiveCompanyId,
            Long workerId,
            Long taskManagerId,
            Long batchManagerId,
            Long botId,
            Boolean botActive,
            LocalDate scheduledDate,
            boolean dueOnDate,
            boolean archivedSource,
            List<Warning> warnings
    ) {

        public RecoveryTaskNode {
            warnings = List.copyOf(warnings);
        }
    }

    public record BadTaskNode(
            long taskId,
            long orderId,
            Long sourceReviewId,
            Long workerId,
            Long botId,
            Boolean botActive,
            LocalDate scheduledDate,
            boolean dueOnDate,
            List<Warning> warnings
    ) {

        public BadTaskNode {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Raw stage totals remain visible independently from the estimate. Nagul units only
     * include the same configured lookahead as the specialist board; cards beyond that
     * window are reported separately and are not charged to {@code estimatedMinutes}.
     */
    public record WorkloadTotals(
            long activeOrderCount,
            long unpublishedReviewCount,
            long newUnits,
            long correctionUnits,
            long nagulUnits,
            long futureNagulUnits,
            long nagulOutsideLookaheadUnits,
            long publishUnits,
            long futurePublishUnits,
            long recoveryUnits,
            long badUnits,
            long activePerformerAssignmentCount,
            long activeExternalCheckCount,
            long attentionExternalCheckCount,
            long estimatedMinutes
    ) {
    }

    public record Warning(
            WarningCode code,
            WarningSeverity severity,
            String message
    ) {
    }

    public enum ReviewStage {
        NAGUL,
        PUBLISH
    }

    public enum WarningSeverity {
        INFO,
        WARNING,
        ERROR
    }

    public enum WarningCode {
        COMPANY_INACTIVE,
        COMPANY_MANAGER_MISMATCH,
        SOURCE_COMPANY_LINK_MISSING,
        SHARED_COMPANY_OWNERSHIP,
        OTHER_WORKER_ACTIVE_ORDERS,
        UNASSIGNED_ACTIVE_ORDERS,
        ORDER_MANAGER_MISMATCH,
        ORDER_WAITING_FOR_CLIENT,
        DECLARED_DETAIL_AMOUNT_MISMATCH,
        DECLARED_REVIEW_COUNT_MISMATCH,
        REVIEW_WORKER_MISMATCH,
        REVIEW_ORDER_NOT_OWNED_BY_SOURCE,
        REVIEW_TEXT_NOT_READY,
        REVIEW_SUPPRESSED_BY_RECOVERY,
        REVIEW_BOT_MISSING,
        REVIEW_BOT_STUB,
        REVIEW_BOT_INACTIVE,
        REVIEW_BOT_OWNER_MISMATCH,
        REVIEW_BOT_DUPLICATED,
        REVIEW_DELAY_BOT_MISMATCH,
        RECOVERY_WORKER_MISMATCH,
        RECOVERY_ORDER_NOT_OWNED_BY_SOURCE,
        RECOVERY_MANAGER_MISMATCH,
        RECOVERY_BOT_MISSING,
        RECOVERY_BOT_INACTIVE,
        ARCHIVED_RECOVERY_SOURCE,
        COMPLETED_RECOVERY_SOURCE,
        BAD_WORKER_MISMATCH,
        BAD_ORDER_NOT_OWNED_BY_SOURCE,
        BAD_BOT_MISSING,
        BAD_BOT_INACTIVE,
        EXTERNAL_CHECK_REQUIRES_ATTENTION
    }
}
