package com.hunt.otziv.r_review.bot;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.config.settings.AppSettingService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewBotCooldownServiceTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");

    @Mock
    private AppSettingService appSettingService;

    @Test
    void markReleasedSetsTwoDayCooldownByDefault() {
        ReviewBotCooldownService service = service();
        Bot bot = bot(10L);

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_COOLDOWN_DAYS, 2)).thenReturn(2);

        service.markReleased(bot, "test");

        assertEquals(LocalDate.now(BUSINESS_ZONE).plusDays(2), bot.getCooldownUntil());
    }

    @Test
    void futureCooldownBlocksAssignmentUntilDateArrives() {
        ReviewBotCooldownService service = service();
        Bot coolingDown = bot(11L);
        coolingDown.setCooldownUntil(LocalDate.now(BUSINESS_ZONE).plusDays(1));
        Bot readyToday = bot(12L);
        readyToday.setCooldownUntil(LocalDate.now(BUSINESS_ZONE));

        assertFalse(service.isAvailableForAssignment(coolingDown));
        assertTrue(service.isAvailableForAssignment(readyToday));
    }

    private ReviewBotCooldownService service() {
        return new ReviewBotCooldownService(appSettingService);
    }

    private Bot bot(Long id) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setActive(true);
        return bot;
    }
}
