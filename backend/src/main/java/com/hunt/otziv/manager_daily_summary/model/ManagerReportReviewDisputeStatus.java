package com.hunt.otziv.manager_daily_summary.model;

public enum ManagerReportReviewDisputeStatus {
    DRAFT,
    OPEN,
    ACCEPTED,
    REJECTED,
    NEEDS_CONTEXT,
    /** System request replaced by source resolution/new evidence; never an owner's acceptance. */
    SUPERSEDED
}
