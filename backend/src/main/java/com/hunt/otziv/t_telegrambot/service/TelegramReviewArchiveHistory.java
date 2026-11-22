package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.client_chat_control.api.ReviewArchiveHistory;
import com.hunt.otziv.t_telegrambot.api.TelegramChatAccess;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Telegram has no history API for bots, but the bot receives every group message when its privacy mode is off or it is
 * an administrator, and the client-chat tracker stores all of them (long polling: Telegram keeps updates for 24 h, so a
 * restart loses nothing). ReviewArchiveHistoryService has already looked for a stored reply since the last reminder
 * before asking this reader; here it is only proven that the stored messages are the whole history: the bot is still
 * in the group and sees all of it. Then «no stored reply» means «no reply». Otherwise nothing is concluded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramReviewArchiveHistory implements ReviewArchiveHistory.Reader {

    private static final String SOURCE = "telegram_saved_messages";

    private final TelegramChatAccess chatAccess;
    private final TelegramService telegram;

    @Override
    public String platform() {
        return "TELEGRAM";
    }

    @Override
    public ReviewArchiveHistory.Check read(ReviewArchiveHistory.Chat chat, LocalDateTime since) {
        LocalDateTime checkedAt = LocalDateTime.now();
        long chatId;
        try {
            chatId = Long.parseLong(chat.chatId());
        } catch (RuntimeException invalid) {
            return result(ReviewArchiveHistory.Outcome.HISTORY_MISSING,
                    "Не определена группа Telegram для проверки переписки.", since, checkedAt);
        }
        var access = chatAccess.check(chatId);
        if ("UNAVAILABLE".equals(access.status())) {
            return result(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE,
                    "Бот Telegram больше не состоит в группе клиента или не может в ней писать: ответы клиента не видны. "
                            + "Верните бота в группу.", since, checkedAt);
        }
        if (!"AVAILABLE".equals(access.status())) {
            return result(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE,
                    "Telegram не подтвердил доступ бота к группе. Система повторит проверку.", since, checkedAt);
        }
        try {
            if (!receivesAllGroupMessages(chatId)) {
                return result(ReviewArchiveHistory.Outcome.HISTORY_MISSING,
                        "Бот Telegram видит в группе не все сообщения (включён режим приватности и бот не администратор). "
                                + "Сделайте бота администратором группы.", since, checkedAt);
            }
        } catch (TelegramApiException | RuntimeException unavailable) {
            log.debug("Telegram archive history check deferred: chatId={}, errorType={}", chatId,
                    unavailable.getClass().getSimpleName());
            return result(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE,
                    "Telegram сейчас не ответил на проверку группы. Система повторит проверку.", since, checkedAt);
        }
        return result(ReviewArchiveHistory.Outcome.CLEAR,
                "Переписка Telegram проверена по сообщениям, которые получил бот; ответов после последнего напоминания нет.",
                since, checkedAt);
    }

    /** Privacy mode off for the bot, or the bot is an administrator of this group: either way it gets every message. */
    private boolean receivesAllGroupMessages(long chatId) throws TelegramApiException {
        User me = telegram.execute(new GetMe());
        if (me == null) return false;
        if (Boolean.TRUE.equals(me.getCanReadAllGroupMessages())) return true;
        var member = telegram.executeGetChatMember(new GetChatMember(Long.toString(chatId), me.getId()));
        return member != null && Set.of("administrator", "creator").contains(member.getStatus());
    }

    private ReviewArchiveHistory.Check result(ReviewArchiveHistory.Outcome outcome, String reason,
                                              LocalDateTime since, LocalDateTime checkedAt) {
        return new ReviewArchiveHistory.Check(outcome, reason, since, checkedAt, 0, SOURCE);
    }
}
