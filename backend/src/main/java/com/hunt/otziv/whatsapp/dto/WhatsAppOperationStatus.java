package com.hunt.otziv.whatsapp.dto;

/** Metadata only. NOT_FOUND is not proof of non-delivery after backup restoration. */
public record WhatsAppOperationStatus(String operationId, String state, String messageId, String envelopeHash) {
    /** Older gateways cannot supply enough evidence for automatic recovery. */
    public WhatsAppOperationStatus(String operationId, String state, String messageId) {
        this(operationId, state, messageId, null);
    }
}
