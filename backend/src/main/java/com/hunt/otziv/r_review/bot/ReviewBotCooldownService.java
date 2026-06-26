package com.hunt.otziv.r_review.bot;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.config.settings.AppSettingService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewBotCooldownService {

    private static final Long STUB_BOT_ID = 1L;
    private static final int DEFAULT_COOLDOWN_DAYS = 2;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");

    private final AppSettingService appSettingService;

    public boolean isAvailableForAssignment(Bot bot) {
        if (bot == null) {
            return false;
        }

        LocalDate cooldownUntil = bot.getCooldownUntil();
        return cooldownUntil == null || !cooldownUntil.isAfter(today());
    }

    public void markReleased(Bot bot, String reason) {
        if (!canCoolDown(bot)) {
            return;
        }

        int cooldownDays = cooldownDays();
        if (cooldownDays <= 0) {
            return;
        }

        LocalDate cooldownUntil = today().plusDays(cooldownDays);
        if (bot.getCooldownUntil() == null || bot.getCooldownUntil().isBefore(cooldownUntil)) {
            bot.setCooldownUntil(cooldownUntil);
            log.info("Bot {} cooled down until {} after release: {}", bot.getId(), cooldownUntil, reason);
        }
    }

    private boolean canCoolDown(Bot bot) {
        return bot != null
                && bot.getId() != null
                && !STUB_BOT_ID.equals(bot.getId());
    }

    private int cooldownDays() {
        return Math.max(0, appSettingService.getInt(
                AppSettingService.REVIEW_ACCOUNT_COOLDOWN_DAYS,
                DEFAULT_COOLDOWN_DAYS
        ));
    }

    private LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }
}
