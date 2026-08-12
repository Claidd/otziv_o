package com.hunt.otziv.whatsapp.service.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.dto.WhatsAppClientStatusDto;
import com.hunt.otziv.whatsapp.dto.WhatsAppChatMessageCursor;
import com.hunt.otziv.whatsapp.dto.WhatsAppReconciledMessage;

import java.util.List;
import java.util.Optional;

public interface WhatsAppService {
    String sendMessage(String clientId, String phone, String message);
    String sendMessageToGroup(String clientId, String groupId, String message);
    List<WhatsAppGroupInfo> listGroups(String clientId);
    default List<WhatsAppGroupInfo> listGroups(String clientId, boolean forceRefresh) {
        return listGroups(clientId);
    }
    Optional<WhatsAppGroupInfo> resolveGroupByInvite(String clientId, String inviteLinkOrCode);
    List<WhatsAppReconciledMessage> reconcileGroupMessages(
            String clientId,
            List<WhatsAppChatMessageCursor> chats
    );
    WhatsAppClientStatusDto getClientStatus(String clientId);
}
