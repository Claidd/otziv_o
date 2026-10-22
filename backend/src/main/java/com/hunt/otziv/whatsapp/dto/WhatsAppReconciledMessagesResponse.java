package com.hunt.otziv.whatsapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhatsAppReconciledMessagesResponse(
        String status,
        String clientId,
        List<WhatsAppReconciledMessage> messages,
        Integer failedGroupCount
) {
}
