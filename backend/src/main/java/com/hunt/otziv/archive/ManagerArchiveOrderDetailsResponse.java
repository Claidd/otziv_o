package com.hunt.otziv.archive;

import java.util.List;

public record ManagerArchiveOrderDetailsResponse(
        ManagerArchiveOrderListItem order,
        String orderComments,
        List<ArchiveOrderDetailItem> details,
        List<ArchiveReviewItem> reviews,
        List<ArchiveBadReviewTaskItem> badReviewTasks,
        List<ArchiveNextOrderRequestItem> nextOrderRequests,
        List<ArchiveZpItem> zp,
        List<ArchivePaymentCheckItem> paymentChecks
) {
}
