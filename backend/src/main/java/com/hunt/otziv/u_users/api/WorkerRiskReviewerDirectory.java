package com.hunt.otziv.u_users.api;

import java.util.List;

/** Identity-owned recipient policy for an already authorized worker-risk explanation. */
public interface WorkerRiskReviewerDirectory {
    /** Active assigned managers, owners and admins, deduplicated and excluding the worker. */
    List<Reviewer> reviewersForWorker(Long workerUserId);

    record Reviewer(Long userId, Long telegramChatId) {}
}
