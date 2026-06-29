package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MaxBotUpdateService {

    private static final String TYPE_BOT_STARTED = "bot_started";
    private static final String TYPE_BOT_ADDED = "bot_added";
    private static final String TYPE_MESSAGE_CREATED = "message_created";

    private final MaxGroupLinkService maxGroupLinkService;
    private final MaxBotClient maxBotClient;
    private final PublicationProgressPreferenceService publicationProgressPreferenceService;
    private final ClientChatMessageTrackerService clientChatMessageTrackerService;

    @Value("${max.bot.username:}")
    private String botUsername;

    public void handleUpdate(JsonNode update) {
        if (update == null || update.isNull()) {
            return;
        }

        String updateType = text(update, "update_type");
        Long chatId = chatId(update);
        Long userId = userId(update);

        if (TYPE_BOT_STARTED.equals(updateType)) {
            String payload = text(update, "payload");
            log.info("MAX bot_started received chatId={} userId={} payloadPresent={}", chatId, userId, hasText(payload));
            Optional<String> response = maxGroupLinkService.handleBotStarted(userId, payload);
            response.ifPresent(message -> sendResponse(chatId, userId, message));
            return;
        }

        if (TYPE_BOT_ADDED.equals(updateType)) {
            log.info("MAX bot_added received chatId={} userId={}", chatId, userId);
            Optional<String> response = maxGroupLinkService.handleBotAdded(chatId, userId);
            response.ifPresent(message -> maxBotClient.sendMessageToChat(chatId, message));
            return;
        }

        if (TYPE_MESSAGE_CREATED.equals(updateType)) {
            String payload = startCommandPayload(update);
            if (hasText(payload)) {
                log.info("MAX /start fallback received chatId={} userId={} payloadPresent=true", chatId, userId);
                Optional<String> response = maxGroupLinkService.handleBotStarted(userId, payload);
                response.ifPresent(message -> sendResponse(chatId, userId, message));
                return;
            }

            String messageText = messageText(update);
            Optional<PublicationProgressPreferenceService.PreferenceUpdate> preferenceUpdate =
                    publicationProgressPreferenceService.handleMaxCommand(chatId, messageText);
            if (preferenceUpdate != null) {
                preferenceUpdate.ifPresent(response -> maxBotClient.sendMessageToChat(chatId, response.message()));
            }
            if (publicationProgressPreferenceService.isPreferenceCommand(messageText)) {
                return;
            }
            trackMessageCreated(update, chatId, userId, messageText);
            return;
        }

        log.debug("MAX update ignored: type={}, chatId={}, userId={}", updateType, chatId, userId);
    }

    private void sendResponse(Long chatId, Long userId, String message) {
        if (chatId != null) {
            maxBotClient.sendMessageToChat(chatId, message);
            return;
        }

        maxBotClient.sendMessageToUser(userId, message);
    }

    private static Long chatId(JsonNode update) {
        Long topLevel = longValue(update, "chat_id");
        if (topLevel != null) {
            return topLevel;
        }

        Long messageRecipientChat = longValue(update.path("message").path("recipient"), "chat_id");
        if (messageRecipientChat != null) {
            return messageRecipientChat;
        }

        Long recipientChat = longValue(update.path("recipient"), "chat_id");
        if (recipientChat != null) {
            return recipientChat;
        }

        Long messageChat = longValue(update.path("message").path("chat"), "chat_id");
        if (messageChat != null) {
            return messageChat;
        }

        return longValue(update.path("chat"), "chat_id");
    }

    private static Long userId(JsonNode update) {
        Long topLevel = longValue(update.path("user"), "user_id");
        if (topLevel != null) {
            return topLevel;
        }

        Long messageSender = longValue(update.path("message").path("sender"), "user_id");
        if (messageSender != null) {
            return messageSender;
        }

        Long sender = longValue(update.path("sender"), "user_id");
        if (sender != null) {
            return sender;
        }

        Long messageRecipientUser = longValue(update.path("message").path("recipient"), "user_id");
        if (messageRecipientUser != null) {
            return messageRecipientUser;
        }

        return longValue(update.path("recipient"), "user_id");
    }

    private static String startCommandPayload(JsonNode update) {
        String messageText = messageText(update);
        if (!hasText(messageText)) {
            return "";
        }

        String normalized = messageText.trim();
        if (!normalized.startsWith("/start")) {
            return "";
        }

        int separator = normalized.indexOf(' ');
        if (separator < 0 || separator + 1 >= normalized.length()) {
            return "";
        }

        return normalized.substring(separator + 1).trim();
    }

    private static String messageText(JsonNode update) {
        return firstText(
                update.path("message").path("body"),
                update.path("body"),
                update
        );
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = text(node, "text");
            if (hasText(value)) {
                return value;
            }
        }

        return "";
    }

    private void trackMessageCreated(JsonNode update, Long chatId, Long userId, String messageText) {
        if (clientChatMessageTrackerService == null || chatId == null || !hasText(messageText)) {
            return;
        }
        if (isKnownBotSender(update)) {
            log.debug("MAX bot/self message ignored chatId={} userId={}", chatId, userId);
            return;
        }
        try {
            clientChatMessageTrackerService.track(new ClientChatMessageCommand(
                    ClientChatPlatform.MAX,
                    ClientChatDirection.INCOMING,
                    String.valueOf(chatId),
                    text(update.path("message").path("chat"), "title"),
                    messageId(update),
                    userId == null ? null : String.valueOf(userId),
                    text(update.path("message").path("sender"), "name"),
                    messageText,
                    messageTime(update)
            ));
        } catch (Exception e) {
            log.warn("MAX message_created tracking failed chatId={}", chatId, e);
        }
    }

    private boolean isKnownBotSender(JsonNode update) {
        JsonNode sender = update.path("message").path("sender");
        if (sender.isMissingNode() || sender.isNull()) {
            sender = update.path("sender");
        }

        if (sender.path("is_bot").asBoolean(false)) {
            return true;
        }

        String type = text(sender, "type");
        if ("bot".equalsIgnoreCase(type)) {
            return true;
        }

        String configuredUsername = normalizeBotUsername(botUsername);
        String senderUsername = normalizeBotUsername(firstTextField(sender, "username", "login", "link"));
        if (hasText(configuredUsername) && configuredUsername.equals(senderUsername)) {
            return true;
        }

        String name = text(sender, "name").trim();
        if ("O Company Bot".equalsIgnoreCase(name)) {
            return true;
        }

        return hasText(senderUsername) && senderUsername.toLowerCase(Locale.ROOT).endsWith("_bot");
    }

    private static String firstTextField(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeBotUsername(String value) {
        if (!hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized
                .replaceFirst("^@", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String messageId(JsonNode update) {
        String textId = text(update.path("message"), "mid");
        if (hasText(textId)) {
            return textId;
        }
        Long numeric = longValue(update.path("message"), "message_id");
        return numeric == null ? null : String.valueOf(numeric);
    }

    private static LocalDateTime messageTime(JsonNode update) {
        Long timestamp = longValue(update.path("message"), "timestamp");
        if (timestamp == null) {
            timestamp = longValue(update, "timestamp");
        }
        if (timestamp == null || timestamp <= 0) {
            return LocalDateTime.now();
        }
        if (timestamp > 10_000_000_000L) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }

        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asLong();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
