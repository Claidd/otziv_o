package com.hunt.otziv.payments.repository;

import java.time.LocalDateTime;

/** Minimal scheduler projection used to merge two independently indexable candidate scans. */
public interface PaymentBankReconciliationCandidateView {

    Long getId();

    LocalDateTime getAttemptedAt();

    LocalDateTime getUpdatedAt();
}
