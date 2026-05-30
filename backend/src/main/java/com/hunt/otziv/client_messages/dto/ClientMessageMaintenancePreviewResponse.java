package com.hunt.otziv.client_messages.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ClientMessageMaintenancePreviewResponse(
        LocalDateTime updatedAt,
        int paymentOverdueDays,
        int publicationStaleDays,
        CompanyStatusPreview companyStatuses,
        PaymentStatusPreview paymentStatuses,
        UnpaidRecoveryPreview unpaidRecovery,
        PublicationPreview publication,
        ArchiveOfferPreview archiveOffers,
        List<ActionItem> suggestedActions
) {
    public record CompanyStatusPreview(
            long shouldMoveToWork,
            long stoppedWithActiveOrders,
            long newOrderWithActiveOrders,
            long bannedWithActiveOrders,
            long workWithoutActiveOrders,
            long newOrderWithoutActiveOrders,
            List<CompanyStatusSample> samplesToWork,
            List<CompanyStatusSample> samplesToStop,
            List<CompanyStatusSample> samplesBannedWithActiveOrders
    ) {
    }

    public record CompanyStatusSample(
            Long companyId,
            String companyTitle,
            String currentStatus,
            long activeOrders,
            String activeOrderStatuses
    ) {
    }

    public record PaymentStatusPreview(
            long invoiceOrReminderTotal,
            long invoiceOrReminderOlderThanThreshold,
            long invoiceOrReminderOlderThanThirtyDays,
            long invoiceOrReminderWithoutActiveState,
            List<OrderRiskSample> overdueSamples
    ) {
    }

    public record UnpaidRecoveryPreview(
            long total,
            long olderThanThreshold,
            long olderThanThreeHundredDays,
            long withoutBadTasks,
            long canCreateBadTasks,
            long withoutPublishedReviews,
            long withPendingBadTasks,
            long allBadTasksDone,
            List<OrderRiskSample> oldSamples
    ) {
    }

    public record PublicationPreview(
            long total,
            long suspicious,
            long olderThanStaleDays,
            long overdueUnpublished,
            long undatedUnpublished,
            long blankOrPlaceholderText,
            long invalidPublicationAccounts,
            long templatePublicationAccountNames,
            long firstFuturePublicationTooFar,
            long longPublishSpan,
            long farFuturePublishDate,
            long oldAllReviewsPublished,
            long oldWithFuturePublishDate,
            List<OrderRiskSample> oldSamples
    ) {
    }

    public record ArchiveOfferPreview(
            long activeStates,
            long dueNow,
            long blockedByActiveOrders,
            long blockedByOpenNextRequest
    ) {
    }

    public record OrderRiskSample(
            Long orderId,
            Long companyId,
            String companyTitle,
            String status,
            long ageDays,
            Integer orderAmount,
            String orderSum,
            long reviews,
            long publishedReviews,
            long badTasks,
            long pendingBadTasks,
            LocalDate maxPublishDate,
            String reason
    ) {
    }

    public record ActionItem(
            String tone,
            String title,
            String description,
            long count
    ) {
    }
}
