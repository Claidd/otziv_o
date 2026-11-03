package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.api.ReviewArchiveHistory;
import com.hunt.otziv.client_chat_control.api.ReminderContactHistory;
import com.hunt.otziv.client_chat_control.model.*;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ReviewArchiveHistoryService implements ReviewArchiveHistory {
    private final ReminderContactHistory whatsapp;
    private final ClientChatMessageRepository messages;
    private final ClientChatUnansweredItemRepository unanswered;
    private final List<Reader> readers;

    @Override public Check inspect(Chat chat, LocalDateTime since) {
        if (chat == null || since == null || chat.chatId() == null || chat.chatId().isBlank())
            return result(Outcome.HISTORY_MISSING,"Не определена группа для проверки переписки.",since,"binding");
        if (hasLocalReply(chat,since))
            return result(Outcome.REPLY_FOUND,"После последнего напоминания найден ответ или незакрытое обращение. Автоархив приостановлен.",since,"saved_messages");
        if ("WHATSAPP".equals(chat.platform())) {
            try {
                boolean reply=whatsapp.hasReplyBlockingArchive(chat.clientId(),chat.chatId(),since);
                return result(reply?Outcome.REPLY_FOUND:Outcome.CLEAR,reply
                        ? "В WhatsApp найден ответ клиента. Автоархив приостановлен."
                        : "История WhatsApp проверена; ответов после напоминания нет.",since,"whatsapp_history");
            } catch (RuntimeException unavailable) {
                return result(Outcome.HISTORY_UNAVAILABLE,"История WhatsApp сейчас недоступна. Система повторит проверку.",since,"whatsapp_history");
            }
        }
        var matching=readers.stream().filter(reader->reader.platform().equals(chat.platform())).toList();
        if (matching.size()==1) {
            try {
                Check check=matching.getFirst().read(chat,since);
                if(check==null || check.outcome()==null || !since.equals(check.since()) || check.checkedAt()==null)
                    throw new IllegalStateException("Incomplete history proof");
                return check;
            }
            catch (RuntimeException unavailable) {
                return result(Outcome.HISTORY_UNAVAILABLE,"История "+chat.platform()+" сейчас недоступна. Система повторит проверку.",since,"provider_history");
            }
        }
        return result(Outcome.HISTORY_MISSING,"TELEGRAM".equals(chat.platform())
                ? "Сохранённые ответы Telegram проверены. Для полной проверки периода с последнего напоминания нужно подключить аккаунт Telegram с доступом к группе. Система продолжит проверять новые ответы."
                : "Источник полной истории этого канала не подключён. Система повторит проверку.",since,"saved_messages");
    }

    @Override public boolean hasLocalReply(Chat chat,LocalDateTime since) {
        var platform=ClientChatPlatform.valueOf(chat.platform());
        return !unanswered.findByPlatformAndChatIdAndStatus(platform,chat.chatId(),ClientChatUnansweredStatus.OPEN).isEmpty()
                || messages.existsByPlatformAndChatIdAndDirectionAndSenderRoleNotAndMessageAtGreaterThanEqual(
                    platform,chat.chatId(),ClientChatDirection.INCOMING,ClientChatSenderRole.BOT,since);
    }
    private Check result(Outcome outcome,String reason,LocalDateTime since,String source) {
        return new Check(outcome,reason,since,LocalDateTime.now(),0,source);
    }
}
