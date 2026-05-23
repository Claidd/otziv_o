package com.hunt.otziv.whatsapp.service.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppUserStatusDto;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.dto.WhatsAppClientStatusDto;

import java.util.List;
import java.util.Optional;

public interface WhatsAppService {
    String sendMessage(String clientId, String phone, String message);
    String sendMessageToGroup(String clientId, String groupId, String message);
    List<WhatsAppGroupInfo> listGroups(String clientId);
    WhatsAppClientStatusDto getClientStatus(String clientId);
    Optional<WhatsAppUserStatusDto> getUserStatusWithLastSeen(String id, String phone);
}
