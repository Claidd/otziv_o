package com.hunt.otziv.manager_control.dto;

/** Delivery evidence and whether it was safe to update the current inbound message. */
public record ManagerClientReplyResolutionResponse(String operationId, String state,
                                                   boolean sourceApplied, String messageId) { }
