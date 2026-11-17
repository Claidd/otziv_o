package com.hunt.otziv.common_billing.dto;

import java.time.LocalDateTime;

public record CommonInvoiceRepricingReview(String operationId, long previousKopecks, long currentKopecks,
        LocalDateTime preparedAt, LocalDateTime dispatchStartedAt, boolean statementReviewAvailable) {}
