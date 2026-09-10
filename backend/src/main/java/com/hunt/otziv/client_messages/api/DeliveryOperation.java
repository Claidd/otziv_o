package com.hunt.otziv.client_messages.api;

/** Public delivery state, distinct from a document's business lifecycle. */
public record DeliveryOperation(String operationId, String status, int attempts, String errorCode) {}
