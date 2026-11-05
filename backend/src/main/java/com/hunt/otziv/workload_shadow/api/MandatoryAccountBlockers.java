package com.hunt.otziv.workload_shadow.api;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read-only: today's work items that the day close counts against a specialist although they have
 * no usable account (no account, or an inactive one). Computed with the workload projection's own
 * source batches and blocker rule, so a notice built from it matches the final day result.
 * Callers pass only worker ids they already resolved; nothing here sends messages or mutates data.
 */
public interface MandatoryAccountBlockers {

    /** The workload day as configured now: business zone, shift window and whether blockers count. */
    WorkloadDay currentDay();

    /** Blocked items per worker id at {@code observedAt} (business-zone local time). */
    Map<Long, List<BlockedTask>> blockedTasks(Collection<Long> workerIds, LocalDateTime observedAt);

    record WorkloadDay(ZoneId zone, LocalTime shiftStart, LocalTime shiftEnd, boolean blockersCountAgainstDay) {
    }

    enum Kind { NAGUL, PUBLISH, BAD, RECOVERY }

    /**
     * {@code recoveryAccountMissing} is true for a recovery task without a login or password: the
     * specialist screen offers «Получить аккаунт» there; every other blocked card offers «смена».
     */
    record BlockedTask(
            long workerId,
            Kind kind,
            Long taskId,
            Long companyId,
            String companyTitle,
            Long orderId,
            boolean recoveryAccountMissing
    ) {
    }
}
