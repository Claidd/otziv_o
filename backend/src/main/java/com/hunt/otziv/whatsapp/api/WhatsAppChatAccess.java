package com.hunt.otziv.whatsapp.api;

/** Read-only account membership evidence; an unreadable invitation is not a delivery failure. */
public interface WhatsAppChatAccess {
    record Result(String status, String reason) { }
    Result check(String clientId, String groupId);
}
