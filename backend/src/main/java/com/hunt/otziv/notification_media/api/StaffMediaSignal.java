package com.hunt.otziv.notification_media.api;

/** Immutable, non-secret context; the recipient always comes from the authenticated actor. */
public record StaffMediaSignal(long userId, String action, String entityType, Long entityId,
                               Long reviewId, String section) {}
