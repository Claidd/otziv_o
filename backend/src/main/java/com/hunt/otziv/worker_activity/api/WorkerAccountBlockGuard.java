package com.hunt.otziv.worker_activity.api;

import org.springframework.security.core.Authentication;

/** Call after task authorization, before account mutation or cooldown consumption. */
public interface WorkerAccountBlockGuard {
    void assertCurrentActorCanBlock(String entityType, Long entityId, Long botId);
    void assertCanBlock(Authentication actor, String entityType, Long entityId, Long botId);
}
