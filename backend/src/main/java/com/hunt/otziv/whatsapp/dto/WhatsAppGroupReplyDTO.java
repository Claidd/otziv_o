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
    /**
     * Only on /webhook/whatsapp-group-edit: the id of the edited message. messageId is then the id of
     * this edit event (unique per edit), message is the new full text and editedAt its epoch second.
     */
    private String editOf;
    private Long editedAt;

    public WhatsAppGroupReplyDTO(String clientId, String groupId, String groupName, String from, String fromName,
                                 String messageId, Long timestamp, boolean fromMe, Boolean systemGenerated, String message) {
        this(clientId, groupId, groupName, from, fromName, messageId, timestamp, fromMe, systemGenerated, message, null, null);
    }
}
