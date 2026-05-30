package com.hunt.otziv.r_review.utils;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.r_review.model.Review;
import java.util.Locale;
import java.util.Set;

public final class ReviewBotPolicy {

    public static final long STUB_BOT_ID = 1L;
    private static final Set<String> TEMPLATE_BOT_NAMES = Set.of(
            "впишите имя фамилию",
            "впиши имя фамилию",
            "впишите фамилию имя",
            "нет доступных аккаунтов"
    );

    private ReviewBotPolicy() {
    }

    public static boolean hasUsablePublicationBot(Review review) {
        return review != null && hasUsablePublicationBot(review.getBot());
    }

    public static boolean hasUsablePublicationBot(Bot bot) {
        if (bot == null || bot.getId() == null || STUB_BOT_ID == bot.getId()) {
            return false;
        }
        return bot.isActive()
                && hasText(bot.getLogin())
                && !isTemplateBotName(bot.getFio());
    }

    public static boolean isWalkedAccount(Bot bot, int counterThreshold) {
        return hasUsablePublicationBot(bot) && bot.getCounter() >= counterThreshold;
    }

    public static boolean isTemplateBotName(String fio) {
        return hasText(fio) && TEMPLATE_BOT_NAMES.contains(fio.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
