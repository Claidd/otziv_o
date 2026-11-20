package com.hunt.otziv.maxbot.service;

import com.hunt.otziv.maxbot.api.MaxChatAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MaxChatAccessService implements MaxChatAccess {
    private final MaxBotClient client;

    @Override public Result check(long chatId) {
        try {
            var chat = client.getChat(chatId);
            if (chat == null || !chat.path("chat_id").isIntegralNumber()
                    || chat.path("chat_id").longValue() != chatId)
                return new Result("UNKNOWN", "access_unconfirmed");
            // https://dev.max.ru/docs-api/objects/Chat: closed/removed/left are explicit states.
            return switch (chat.path("status").asText()) {
                case "closed" -> new Result("UNAVAILABLE", "max_chat_closed");
                case "removed", "left" -> new Result("UNAVAILABLE", "bot_not_member");
                case "active" -> new Result("AVAILABLE", "member");
                default -> new Result("UNKNOWN", "access_unconfirmed");
            };
        } catch (RuntimeException unavailable) {
            return new Result("UNKNOWN", "access_unconfirmed");
        }
    }
}
