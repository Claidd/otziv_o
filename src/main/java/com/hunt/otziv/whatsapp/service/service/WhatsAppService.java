package com.hunt.otziv.whatsapp.service.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppUserStatusDto;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WhatsAppService {
    String sendMessage(String clientId, String phone, String message);
    String sendMessageToGroup(String clientId, String groupId, String message);

    Optional<LocalDateTime> fetchLastSeen(String id, String s);

    Optional<Boolean> isRegisteredInWhatsApp(String id, String s);

    Optional<WhatsAppUserStatusDto> checkActiveUser(String id, String s);

    Optional<WhatsAppUserStatusDto> getUserStatusWithLastSeen(String id, String phone);
}
