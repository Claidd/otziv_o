package com.hunt.otziv.payments.dto;

public record ManualPaymentDisputeRequest(Long expectedReviewId, Boolean recipientReportedMissing, String note) { }
