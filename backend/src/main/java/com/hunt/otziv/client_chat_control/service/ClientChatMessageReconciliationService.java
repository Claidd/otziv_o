package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.dto.ClientChatReconciliationResult;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
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
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientChatMessageReconciliationService implements com.hunt.otziv.client_chat_control.api.ReminderContactHistory {

    private final ClientChatUnansweredItemRepository unansweredRepository;
    private final ClientChatMessageRepository messageRepository;
    private final ClientChatMessageTrackerService trackerService;
    private final WhatsAppService whatsAppService;

    /** Strict, read-only transport lookup. Any message delays a reminder, including staff and bot messages. */
    public LocalDateTime latestReminderContact(String clientId, String chatId, LocalDateTime since) {
        if (!hasText(clientId) || !hasText(chatId) || since == null)
            throw new IllegalArgumentException("Reminder chat identity is missing");
        return whatsAppService.reconcileGroupMessagesForReceipt(clientId,
                List.of(new WhatsAppChatMessageCursor(chatId, since.atZone(ZoneId.systemDefault()).toEpochSecond())))
                .stream().filter(message -> message != null && chatId.equals(message.groupId()))
                .map(message -> {
                    if (message.timestamp() == null || message.timestamp() <= 0)
                        throw new IllegalStateException("Reminder history timestamp is missing");
                    return messageAt(message.timestamp());
                }).max(Comparator.naturalOrder()).orElse(since);
    }

    /** A fresh answer or a previously unresolved client reply prevents automatic archival. */
    public boolean hasReplyBlockingArchive(String clientId, String chatId, LocalDateTime since) {
        if (!hasText(clientId) || !hasText(chatId) || since == null)
            throw new IllegalArgumentException("Archive chat identity is missing");
        if (hasOpenReply(chatId)) return true;
        return whatsAppService.reconcileGroupMessagesForReceipt(clientId,
                List.of(new WhatsAppChatMessageCursor(chatId, since.atZone(ZoneId.systemDefault()).toEpochSecond())))
                .stream().filter(message -> message != null && chatId.equals(message.groupId()))
                .anyMatch(message -> {
                    if (message.timestamp() == null || message.timestamp() <= 0)
                        throw new IllegalStateException("Archive history timestamp is missing");
                    return !Boolean.TRUE.equals(message.fromMe()) && !Boolean.TRUE.equals(message.systemGenerated());
                });
    }

    /**
     * Read-only: the invite link that the connected WhatsApp account currently sees for a group (WhatsApp
     * reveals it only to group administrators). It reads the gateway's group list; it never changes a
     * company's bound group, never resets the invite and sends nothing.
     */
    public java.util.Optional<String> currentWhatsAppInviteLink(String clientId, String groupId) {
        if (!hasText(clientId) || !hasText(groupId)) {
            return java.util.Optional.empty();
        }
        String group = groupId.trim();
        List<com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo> groups = whatsAppService.listGroups(clientId.trim());
        return (groups == null ? List.<com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo>of() : groups).stream()
                .filter(info -> info != null && info.groupId() != null && group.equals(info.groupId().trim()))
                .map(com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo::inviteLink)
                .filter(ClientChatMessageReconciliationService::hasText)
                .map(String::trim)
                .map(link -> link.toLowerCase(Locale.ROOT).contains("chat.whatsapp.com/")
                        ? (link.startsWith("http") ? link : "https://" + link)
                        : "https://chat.whatsapp.com/" + link)
                .findFirst();
    }

    public boolean hasOpenReply(String chatId) {
        return !unansweredRepository.findByPlatformAndChatIdAndStatus(ClientChatPlatform.WHATSAPP,
                chatId, ClientChatUnansweredStatus.OPEN).isEmpty();
    }

    /**
     * «Проверить ответ»: synchronizes the manager's open WhatsApp chats, then closes every open card
     * (any messenger) whose reply is already stored, for example a reply recorded before its late
     * question arrived. A reply that was never received keeps the card open.
     */
    public ClientChatReconciliationResult reconcileOpenWhatsAppMessages(Manager manager) {
        if (manager == null || manager.getId() == null) {
            return new ClientChatReconciliationResult(0, 0, 0, 0, 0);
        }
        ClientChatReconciliationResult synced = new ClientChatReconciliationResult(0, 0, 0, 0, 0);
        RuntimeException syncFailure = null;
        if (hasText(manager.getClientId())) {
            try {
                synced = synchronizeOpenWhatsAppChats(manager);
            } catch (RuntimeException failure) {
                // The stored history is still checked; the failure is reported only if it found nothing.
                syncFailure = failure;
            }
        }
        int closedFromHistory = trackerService.closeOpenItemsAnsweredInHistory(manager);
        if (closedFromHistory == 0) {
            if (syncFailure != null) {
                throw syncFailure;
            }
            return synced;
        }
        return new ClientChatReconciliationResult(
                synced.requestedChats(),
                synced.receivedMessages(),
                synced.openBefore(),
                Math.max(0, synced.openAfter() - closedFromHistory),
                synced.closedItems() + closedFromHistory
        );
    }

    private ClientChatReconciliationResult synchronizeOpenWhatsAppChats(Manager manager) {

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

        int reconciledMessages = reconcileWhatsAppMessages(manager, earliestOpenMessageByChat);
        int openAfter = openItems(manager).size();
        return new ClientChatReconciliationResult(
                earliestOpenMessageByChat.size(),
                reconciledMessages,
                openBefore.size(),
                openAfter,
                Math.max(0, openBefore.size() - openAfter)
        );
    }

    public boolean reconcileWhatsAppGroupContainsOutgoingText(
            Manager manager,
            String groupId,
            LocalDateTime from,
            String expectedText
    ) {
        if (manager == null || !hasText(manager.getClientId()) || !hasText(groupId) || !hasText(expectedText)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime fromInclusive = from == null ? now.minusDays(1) : from.minusMinutes(5);
        LocalDateTime toExclusive = now.plusMinutes(5);
        String chatId = groupId.trim();
        reconcileWhatsAppMessages(manager, Map.of(chatId, fromInclusive));
        String proof = deliveryProof(expectedText);
        if (!hasText(proof)) {
            return false;
        }
        return messageRepository
                .findByPlatformAndChatIdAndDirectionAndMessageAtBetweenOrderByMessageAtAscIdAsc(
                        ClientChatPlatform.WHATSAPP,
                        chatId,
                        ClientChatDirection.OUTGOING,
                        fromInclusive,
                        toExclusive
                )
                .stream()
                .map(ClientChatMessage::getMessageText)
                .anyMatch(message -> containsProof(message, proof));
    }

    private int reconcileWhatsAppMessages(Manager manager, Map<String, LocalDateTime> earliestMessageByChat) {
        if (earliestMessageByChat == null || earliestMessageByChat.isEmpty()) {
            return 0;
        }
        List<WhatsAppChatMessageCursor> cursors = earliestMessageByChat.entrySet().stream()
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
        return messages.size();
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

    private String deliveryProof(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int payIndex = lower.indexOf("/pay/");
        if (payIndex >= 0) {
            int start = payIndex + 1;
            int end = start;
            while (end < text.length() && !isLinkTerminator(text.charAt(end))) {
                end++;
            }
            return text.substring(start, end).trim();
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160);
    }

    private boolean containsProof(String message, String proof) {
        return hasText(message)
                && hasText(proof)
                && message.toLowerCase(Locale.ROOT).contains(proof.toLowerCase(Locale.ROOT));
    }

    private boolean isLinkTerminator(char ch) {
        return Character.isWhitespace(ch)
                || ch == ')'
                || ch == ']'
                || ch == '>'
                || ch == '"'
                || ch == '\'';
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
