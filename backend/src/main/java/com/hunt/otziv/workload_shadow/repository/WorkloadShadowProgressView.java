package com.hunt.otziv.workload_shadow.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Compact projection shared by live and finalized workload percentages.
 */
public interface WorkloadShadowProgressView {

    Long getWorkerId();

    Long getCompletedUnits();

    Long getEligibleUnits();

    Long getLateExcludedUnits();

    Long getExternalBlockedUnits();

    BigDecimal getProgressPercent();

    Long getReached100();

    Long getReached100Once();

    LocalDateTime getFirstReached100At();

    LocalDateTime getLastReached100At();
}
