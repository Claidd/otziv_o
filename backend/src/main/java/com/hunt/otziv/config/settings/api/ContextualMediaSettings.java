package com.hunt.otziv.config.settings.api;

/** Read-only permission and shared daily illustration budget. No recipient authority. */
public interface ContextualMediaSettings {
    boolean enabled();
    int maxPerDay();
}
