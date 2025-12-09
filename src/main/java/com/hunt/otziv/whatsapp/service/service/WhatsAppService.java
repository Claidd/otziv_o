package com.hunt.otziv.whatsapp.service.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppUserStatusDto;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WhatsAppService {
    String sendMessage(String clientId, String phone, String message);
    String sendMessageToGroup(String clientId, String groupId, String message);
    Optional<WhatsAppUserStatusDto> getUserStatusWithLastSeen(String id, String phone);
}
