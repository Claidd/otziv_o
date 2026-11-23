package com.hunt.otziv.payments.dto;

import java.time.LocalDateTime;

/**
 * One payment matter that waits for a person's decision in «Банк». {@code kind} is OWNER_APPROVAL,
 * SPECIALIST_REVIEW, LINK_RECONCILIATION or CLIENT_REPORTED; {@code since} is when the wait began.
 */
public record BankAttentionItemResponse(
        String kind,
        String title,
        String detail,
        Long orderId,
        String companyTitle,
        String orderStatus,
        Long paymentLinkId,
        Long approvalId,
        Long reviewId,
        Long amountKopecks,
        LocalDateTime since
) {
}
