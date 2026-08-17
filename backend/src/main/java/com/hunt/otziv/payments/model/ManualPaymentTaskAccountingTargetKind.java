package com.hunt.otziv.payments.model;

/**
 * Explicit accounting destination of money sent through a manual payment task.
 * Bank-facing requisites are deliberately kept outside this enum and must never
 * be used to infer the destination.
 */
public enum ManualPaymentTaskAccountingTargetKind {
    /** Migrated task that still requires a deliberate one-time binding. */
    UNRESOLVED,
    /** External person: the money is accounted only against the task. */
    EXTERNAL_TASK,
    /** Owner received the money; there is no contractor profile. */
    OWNER,
    /** Exact specialist contractor profile received the money. */
    SPECIALIST,
    /** Exact manager contractor profile received the money. */
    MANAGER
}
