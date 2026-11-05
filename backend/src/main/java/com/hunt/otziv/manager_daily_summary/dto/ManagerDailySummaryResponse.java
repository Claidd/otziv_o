package com.hunt.otziv.manager_daily_summary.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ManagerDailySummaryResponse(
        LocalDate date,
        Long managerId,
        Long managerUserId,
        String managerName,
        int score,
        String grade,
        long taskTotal,
        long taskCompleted,
        long taskOpen,
        long taskAutoClosed,
        long taskResolved,
        long taskActionTaken,
        long taskDeferred,
        long taskAcknowledged,
        BigDecimal taskProgressPercent,
        long overdueCount,
        long riskCount,
        long unansweredCount,
        long taskOtherOpen,
        long firstReplyCount,
        long firstReplyAverageSeconds,
        long firstReplyMedianSeconds,
        long allReplyAverageSeconds,
        long allReplyMedianSeconds,
        long allReplyP90Seconds,
        long replyCount,
        long repliesInSla,
        long problemCount,
        long problemResolvedCount,
        long problemActionTakenCount,
        long problemOpenCount,
        long problemResolutionAverageSeconds,
        long siteActiveSeconds,
        long messengerActiveSeconds,
        long confirmedActiveSeconds,
        long leadActionCount,
        long targetSlaCount,
        long targetSlaMetCount,
        long hardSlaBreachCount,
        long controlledSeconds,
        long cleanQueueSeconds,
        int dayStars,
        String dayStatus,
        long xpEarned,
        String aggregationStatus,
        java.time.LocalDateTime snapshotAt
) {

    /**
     * The same day without {@code count} still-open unanswered messages: used by the personal
     * audit for messages that are not a remark yet. Stored metrics are not changed.
     */
    public ManagerDailySummaryResponse withoutUnanswered(long count) {
        long removed = Math.min(Math.max(0, count), Math.max(0, unansweredCount));
        if (removed == 0) {
            return this;
        }
        return new ManagerDailySummaryResponse(date, managerId, managerUserId, managerName, score, grade,
                taskTotal, taskCompleted, Math.max(0, taskOpen - removed), taskAutoClosed, taskResolved,
                taskActionTaken, taskDeferred, taskAcknowledged, taskProgressPercent, overdueCount, riskCount,
                unansweredCount - removed, taskOtherOpen, firstReplyCount, firstReplyAverageSeconds,
                firstReplyMedianSeconds, allReplyAverageSeconds, allReplyMedianSeconds, allReplyP90Seconds,
                replyCount, repliesInSla, problemCount, problemResolvedCount, problemActionTakenCount,
                problemOpenCount, problemResolutionAverageSeconds, siteActiveSeconds, messengerActiveSeconds,
                confirmedActiveSeconds, leadActionCount, targetSlaCount, targetSlaMetCount, hardSlaBreachCount,
                controlledSeconds, cleanQueueSeconds, dayStars, dayStatus, xpEarned, aggregationStatus, snapshotAt);
    }
}
