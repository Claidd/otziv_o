package com.hunt.otziv.manager_control.dto;

public record ManagerActionBalance(
        long total,
        long handledByManager,
        long autoClosed,
        long remaining,
        long resolved,
        long actionTaken,
        long deferred,
        long acknowledged,
        long overdueRemaining,
        long riskRemaining,
        long unansweredRemaining,
        long otherRemaining
) {
    public boolean isConsistent() {
        return total == handledByManager + autoClosed + remaining
                && handledByManager == resolved + actionTaken + deferred + acknowledged
                && remaining == overdueRemaining + riskRemaining + unansweredRemaining + otherRemaining;
    }
}
