package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.dto.ClientChatReconciliationResult;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.whatsapp.dto.WhatsAppChatMessageCursor;
import com.hunt.otziv.whatsapp.dto.WhatsAppReconciledMessage;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientChatMessageReconciliationService {

    private final ClientChatUnansweredItemRepository unansweredRepository;
    private final ClientChatMessageTrackerService trackerService;
    private final WhatsAppService whatsAppService;

    public ClientChatReconciliationResult reconcileOpenWhatsAppMessages(Manager manager) {
        if (manager == null || manager.getId() == null || !hasText(manager.getClientId())) {
            return new ClientChatReconciliationResult(0, 0, 0, 0, 0);
        }

        List<ClientChatUnansweredItem> openBefore = openItems(manager);
        Map<String, LocalDateTime> earliestOpenMessageByChat = new LinkedHashMap<>();
        for (ClientChatUnansweredItem item : openBefore) {
            if (!hasText(item.getChatId()) || item.getLastClientMessageAt() == null) {
                continue;
            }
            earliestOpenMessageByChat.merge(
                    item.getChatId().trim(),
                    item.getLastClientMessageAt(),
                    (left, right) -> left.isBefore(right) ? left : right
            );
        }
        if (earliestOpenMessageByChat.isEmpty()) {
            return new ClientChatReconciliationResult(0, 0, openBefore.size(), openBefore.size(), 0);
        }

        List<WhatsAppChatMessageCursor> cursors = earliestOpenMessageByChat.entrySet().stream()
                .map(entry -> new WhatsAppChatMessageCursor(
                        entry.getKey(),
                        entry.getValue().minusSeconds(1).atZone(ZoneId.systemDefault()).toEpochSecond()
                ))
                .toList();
        List<WhatsAppReconciledMessage> messages = whatsAppService
                .reconcileGroupMessages(manager.getClientId(), cursors)
                .stream()
                .filter(message -> message != null && hasText(message.groupId()) && hasText(message.messageId()))
                .sorted(Comparator
                        .comparingLong((WhatsAppReconciledMessage message) ->
                                message.timestamp() == null ? 0L : message.timestamp())
                        .thenComparing(WhatsAppReconciledMessage::messageId))
                .toList();
        for (WhatsAppReconciledMessage message : messages) {
            boolean fromMe = Boolean.TRUE.equals(message.fromMe());
            ClientChatSenderRole senderRoleOverride = Boolean.TRUE.equals(message.systemGenerated())
                    ? ClientChatSenderRole.BOT
                    : fromMe ? ClientChatSenderRole.STAFF : null;
            trackerService.track(new ClientChatMessageCommand(
                    ClientChatPlatform.WHATSAPP,
                    fromMe ? ClientChatDirection.OUTGOING : ClientChatDirection.INCOMING,
                    message.groupId(),
                    message.groupName(),
                    message.messageId(),
                    message.from(),
                    message.fromName(),
                    message.message(),
                    messageAt(message.timestamp())
            ), senderRoleOverride);
        }

        int openAfter = openItems(manager).size();
        return new ClientChatReconciliationResult(
                cursors.size(),
                messages.size(),
                openBefore.size(),
                openAfter,
                Math.max(0, openBefore.size() - openAfter)
        );
    }

    private List<ClientChatUnansweredItem> openItems(Manager manager) {
        return unansweredRepository.findByManagerAndPlatformAndStatus(
                manager,
                ClientChatPlatform.WHATSAPP,
                ClientChatUnansweredStatus.OPEN
        );
    }

    private LocalDateTime messageAt(Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
