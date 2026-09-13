package com.hunt.otziv.config.settings.api;

/** Read-only preparation policy; card authorization remains in the action workflow. */
public interface ClientChatReviewSettings {
    boolean prefetchEnabled();
}
