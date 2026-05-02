package com.hunt.otziv.whatsapp.dto;

import lombok.Data;

@Data
public class WhatsAppReplyDTO {
    private String clientId;
    private String from;
    private String message;
}
