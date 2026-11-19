package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.t_telegrambot.api.TelegramChatAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Service
@RequiredArgsConstructor
public class TelegramChatAccessService implements TelegramChatAccess {
    private final TelegramService telegram;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result check(long chatId) {
        // The production-like stand must not probe real client chats either.
        if (!telegram.canSendDocuments()) return new Result("UNKNOWN", "transport_disabled");
        try {
            var chat = telegram.executeGetChat(new GetChat(Long.toString(chatId)));
            if (chat == null) return new Result("UNKNOWN", "empty_response");
            long botId = Long.parseLong(telegram.getBotToken().split(":", 2)[0]);
            var member = telegram.executeGetChatMember(new GetChatMember(Long.toString(chatId), botId));
            if (member == null) return new Result("UNKNOWN", "empty_response");
            if (member.getStatus() == null || !java.util.Set.of("creator","administrator","member","restricted","left","kicked").contains(member.getStatus()))
                return new Result("UNKNOWN", "membership_unconfirmed");
            if ("left".equals(member.getStatus()) || "kicked".equals(member.getStatus()))
                return new Result("UNAVAILABLE", "bot_not_member");
            if (member instanceof ChatMemberRestricted restricted && !Boolean.TRUE.equals(restricted.getIsMember()))
                return new Result("UNAVAILABLE", "bot_not_member");
            if (member instanceof ChatMemberRestricted restricted && !Boolean.TRUE.equals(restricted.getCanSendMessages()))
                return new Result("UNAVAILABLE", "chat_permission_denied");
            if ("member".equals(member.getStatus()) && chat.getPermissions() != null
                    && !Boolean.TRUE.equals(chat.getPermissions().getCanSendMessages()))
                return new Result("UNAVAILABLE", "chat_permission_denied");
            // Channels have different publishing permissions; do not infer them from membership.
            if (!"group".equals(chat.getType()) && !"supergroup".equals(chat.getType()))
                return new Result("UNKNOWN", "unsupported_chat_type");
            return new Result("AVAILABLE", "member");
        } catch (TelegramApiException failure) {
            var diagnostic = TelegramDeliveryFailure.describe(failure);
            boolean definitive = (Integer.valueOf(400).equals(diagnostic.apiCode())
                    || Integer.valueOf(403).equals(diagnostic.apiCode()))
                    && java.util.Set.of("chat_not_found", "bot_removed", "bot_blocked", "chat_permission_denied")
                        .contains(diagnostic.reason());
            return new Result(definitive ? "UNAVAILABLE" : "UNKNOWN", diagnostic.reason());
        } catch (RuntimeException unavailable) {
            return new Result("UNKNOWN", "transport_or_response_failure");
        }
    }
}
