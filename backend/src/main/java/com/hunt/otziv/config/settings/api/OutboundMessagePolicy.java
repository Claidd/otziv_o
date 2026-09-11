package com.hunt.otziv.config.settings.api;

/** Fresh dispatch permission. A failed settings read must be interpreted as paused. */
public interface OutboundMessagePolicy {
    boolean clientMessagesEnabled();
}
