package com.hunt.otziv.whatsapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppGroupReplyDTO {
    private String clientId;
    private String groupId;
    private String groupName;
    private String from;
    private String fromName;
    private String messageId;
    private Long timestamp;
    private boolean fromMe;
    /**
     * Null means that the gateway predates outbound-message classification.
     * True is reserved for messages sent through the gateway's own send-group endpoint.
     */
    private Boolean systemGenerated;
    private String message;
}
