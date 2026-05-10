package com.hunt.otziv.archive;

public record ArchiveCandidateCounts(
        long orders,
        long orderDetails,
        long reviews,
        long badReviewTasks,
        long nextOrderRequests,
        long zp,
        long paymentCheck
) {
    boolean isEmpty() {
        return orders == 0
                && orderDetails == 0
                && reviews == 0
                && badReviewTasks == 0
                && nextOrderRequests == 0
                && zp == 0
                && paymentCheck == 0;
    }
}
