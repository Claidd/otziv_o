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
    private String message;
}
