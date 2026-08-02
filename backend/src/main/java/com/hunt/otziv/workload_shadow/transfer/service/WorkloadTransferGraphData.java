package com.hunt.otziv.workload_shadow.transfer.service;

import java.time.LocalDate;
import java.util.List;

record WorkloadTransferGraphData(
        long sourceWorkerId,
        long managerId,
        LocalDate date,
        LocalDate nagulLookaheadDate,
        EstimateRates estimateRates,
        List<CompanyRow> companies,
        List<CompanyWorkerLinkRow> companyWorkerLinks,
        List<CompanyOrderOwnershipRow> companyOrderOwnership,
        List<OrderRow> orders,
        List<DetailRow> details,
        List<ReviewRow> reviews,
        List<RecoveryRow> recoveryTasks,
        List<BadRow> badTasks,
        List<PerformerCountRow> performerCounts,
        List<ExternalCheckCountRow> externalCheckCounts
) {

    record EstimateRates(
            int newMinutesPerCard,
            int correctionMinutesPerOrder,
            int walkMinutesPerCard,
            int publishMinutesPerCard,
            int recoveryMinutesPerTask,
            int badMinutesPerTask
    ) {
    }

    record CompanyRow(
            long companyId,
            String companyTitle,
            boolean companyActive,
            String companyStatus,
            Long managerId
    ) {
    }

    record CompanyWorkerLinkRow(long companyId, long workerId) {
    }

    record CompanyOrderOwnershipRow(long companyId, Long workerId, long activeOrderCount) {
    }

    record OrderRow(
            long orderId,
            long companyId,
            String status,
            Long workerId,
            Long managerId,
            boolean waitingForClient,
            boolean clientTextExpected,
            LocalDate createdDate,
            LocalDate changedDate,
            int declaredOrderUnits
    ) {
    }

    record DetailRow(
            long orderId,
            int declaredUnits,
            int actualReviewCount,
            int pendingReviewCount
    ) {
    }

    record ReviewRow(
            long reviewId,
            long orderId,
            long companyId,
            Long workerId,
            Long botId,
            Boolean botActive,
            Long botOwnerWorkerId,
            LocalDate publicationDate,
            boolean walked,
            boolean textReady,
            boolean orderWaitingForClient,
            long activeBotReviewCount,
            Long accountWalkDelayBotId
    ) {
    }

    record RecoveryRow(
            long taskId,
            Long orderId,
            long companyId,
            Long archiveCompanyId,
            Long workerId,
            Long taskManagerId,
            Long batchManagerId,
            Long botId,
            Boolean botActive,
            LocalDate scheduledDate,
            boolean archivedSource,
            Long orderWorkerId,
            boolean orderComplete
    ) {
        RecoveryRow(
                long taskId,
                Long orderId,
                long companyId,
                Long archiveCompanyId,
                Long workerId,
                Long taskManagerId,
                Long batchManagerId,
                Long botId,
                Boolean botActive,
                LocalDate scheduledDate,
                boolean archivedSource
        ) {
            this(
                    taskId,
                    orderId,
                    companyId,
                    archiveCompanyId,
                    workerId,
                    taskManagerId,
                    batchManagerId,
                    botId,
                    botActive,
                    scheduledDate,
                    archivedSource,
                    null,
                    false
            );
        }
    }

    record BadRow(
            long taskId,
            long orderId,
            long companyId,
            Long sourceReviewId,
            Long workerId,
            Long botId,
            Boolean botActive,
            LocalDate scheduledDate
    ) {
    }

    record PerformerCountRow(long reviewId, long activeCount) {
    }

    record ExternalCheckCountRow(long reviewId, long activeCount, long attentionCount) {
    }
}
