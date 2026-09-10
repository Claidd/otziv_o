package com.hunt.otziv.whatsapp.api;

/** Persists a command in the caller's transaction; never contacts the provider. */
public interface WhatsAppQueuedMessages {
    void enqueue(String operationId, String clientId, String groupId, String message);
}
