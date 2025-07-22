package com.hunt.otziv.whatsapp.dto;


import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WhatsAppUserStatusDto {
    private String status;     // "ok", "unknown", "not_whatsapp"
    private Boolean registered; // true / false
    private String lastSeen;   // ISO-8601, например: "2025-06-10T13:44:00Z"
    private String rawLastSeen;
    private LocalDateTime parsedLastSeen;
    private String stage;
}

