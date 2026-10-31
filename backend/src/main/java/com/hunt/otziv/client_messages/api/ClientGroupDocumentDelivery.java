package com.hunt.otziv.client_messages.api;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;

/** Confirmed document send outside DB locks, using an immutable destination and operation identity. */
public interface ClientGroupDocumentDelivery {
    ClientMessageSendResult send(String client,String destination,String caption,byte[] bytes,String filename,String operationId);
}
