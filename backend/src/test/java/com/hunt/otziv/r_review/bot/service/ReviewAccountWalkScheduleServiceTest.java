package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAccountWalkScheduleServiceTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T04:00:00Z"), BUSINESS_ZONE);

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Test
    void counterTwoIsWalkedAndCounterOneIsNotWalked() {
        ReviewAccountWalkScheduleService service = service();

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(2);

        assertFalse(service.isWalkedAccount(bot(1)));
        assertTrue(service.isWalkedAccount(bot(2)));
    }

    @Test
    void onlyCounterZeroOrOneIsEligibleForNagul() {
        ReviewAccountWalkScheduleService service = service();
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(2);

        assertTrue(service.isEligibleForNagul(bot(0)));
        assertTrue(service.isEligibleForNagul(bot(1)));
        assertFalse(service.isEligibleForNagul(bot(2)));

        Bot withoutPassword = bot(1);
        withoutPassword.setPassword(" ");
        assertFalse(service.isEligibleForNagul(withoutPassword));
    }

    @Test
    void walkedToUnwalkedShiftsOnlyFollowingReviewsThatBecomeTooClose() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(100L);
        Review previous = review(1L, details, LocalDate.of(2026, 7, 14), false);
        Review trigger = review(2L, details, LocalDate.of(2026, 7, 16), false);
        Review closeFollowing = review(3L, details, LocalDate.of(2026, 7, 18), false);
        Review farFollowing = review(4L, details, LocalDate.of(2026, 7, 25), false);
        trigger.setBot(bot(1));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS, 2)).thenReturn(2);
        when(reviewRepository.findAllByOrderIdForAccountWalkSchedule(100L))
                .thenReturn(List.of(previous, trigger, closeFollowing, farFollowing));

        service.synchronizeAfterAccountChange(trigger);

        assertFalse(trigger.isVigul());
        assertEquals(3, trigger.getAccountWalkDelayDays());
        assertEquals(3, closeFollowing.getAccountWalkDelayDays());
        assertEquals(0, farFollowing.getAccountWalkDelayDays());
        assertEquals(LocalDate.of(2026, 7, 14), previous.getPublishedDate());
        assertEquals(LocalDate.of(2026, 7, 19), trigger.getPublishedDate());
        assertEquals(LocalDate.of(2026, 7, 21), closeFollowing.getPublishedDate());
        assertEquals(LocalDate.of(2026, 7, 25), farFollowing.getPublishedDate());
        assertEquals(11L, trigger.getAccountWalkDelayBotId());
        assertEquals(LocalDate.of(2026, 7, 19), trigger.getAccountWalkNotBefore());
        verify(reviewRepository).saveAll(List.of(previous, trigger, closeFollowing, farFollowing));
    }

    @Test
    void completedWalkKeepsReservedPublicationDates() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(200L);
        LocalDate restoredTriggerDate = futureBusinessDate(10);
        LocalDate restoredFollowingDate = restoredTriggerDate.plusDays(2);
        Review trigger = review(10L, details, restoredTriggerDate.plusDays(2), false);
        Review following = review(11L, details, restoredFollowingDate.plusDays(2), false);
        Review untouched = review(12L, details, restoredFollowingDate.plusDays(5), false);
        trigger.setAccountWalkDelayDays(2);
        following.setAccountWalkDelayDays(2);
        trigger.setAccountWalkDelayBotId(11L);
        trigger.setAccountWalkNotBefore(restoredTriggerDate.plusDays(2));
        trigger.setBot(bot(3));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);
        service.synchronizeAfterAccountChange(trigger);

        assertTrue(trigger.isVigul());
        assertEquals(0, trigger.getAccountWalkDelayDays());
        assertEquals(2, following.getAccountWalkDelayDays());
        assertEquals(0, untouched.getAccountWalkDelayDays());
        assertEquals(restoredTriggerDate.plusDays(2), trigger.getPublishedDate());
        assertEquals(restoredFollowingDate.plusDays(2), following.getPublishedDate());
        assertEquals(restoredFollowingDate.plusDays(5), untouched.getPublishedDate());
        assertEquals(null, trigger.getAccountWalkDelayBotId());
        assertEquals(null, trigger.getAccountWalkNotBefore());
        verify(reviewRepository, never()).saveAll(anyList());
    }

    @Test
    void unwalkedToWalkedDoesNotRestorePublicationDateIntoPast() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(250L);
        LocalDate currentFutureDate = LocalDate.now().plusDays(1);
        Review trigger = review(15L, details, currentFutureDate, false);
        trigger.setAccountWalkDelayDays(2);
        trigger.setBot(bot(6));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);
        service.synchronizeAfterAccountChange(trigger);

        assertTrue(trigger.isVigul());
        assertEquals(0, trigger.getAccountWalkDelayDays());
        assertEquals(currentFutureDate, trigger.getPublishedDate());
        verify(reviewRepository, never()).saveAll(anyList());
    }

    @Test
    void sameWalkStateDoesNotShiftDates() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(300L);
        Review trigger = review(20L, details, LocalDate.of(2026, 6, 8), false);
        trigger.setBot(bot(4));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);

        service.synchronizeAfterAccountChange(trigger);

        assertTrue(trigger.isVigul());
        assertEquals(0, trigger.getAccountWalkDelayDays());
        assertEquals(LocalDate.of(2026, 6, 8), trigger.getPublishedDate());
        verify(reviewRepository, never()).findAllByOrderIdForAccountWalkSchedule(300L);
        verify(reviewRepository, never()).saveAll(anyList());
    }

    @Test
    void everyUnwalkedAssignmentShiftsDatesWithoutUiSourceFlag() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(400L);
        Review trigger = review(30L, details, LocalDate.of(2026, 7, 16), false);
        Review following = review(31L, details, LocalDate.of(2026, 7, 18), false);
        trigger.setBot(bot(0));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS, 2)).thenReturn(2);
        when(reviewRepository.findAllByOrderIdForAccountWalkSchedule(400L))
                .thenReturn(List.of(trigger, following));

        service.synchronizeAfterAccountChange(trigger);

        assertFalse(trigger.isVigul());
        assertEquals(3, trigger.getAccountWalkDelayDays());
        assertEquals(3, following.getAccountWalkDelayDays());
        assertEquals(LocalDate.of(2026, 7, 19), trigger.getPublishedDate());
        assertEquals(LocalDate.of(2026, 7, 21), following.getPublishedDate());
        verify(reviewRepository).saveAll(List.of(trigger, following));
    }

    @Test
    void repeatedUnwalkedAssignmentDoesNotApplyWalkDelayTwice() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(500L);
        Review trigger = review(40L, details, LocalDate.of(2026, 7, 19), false);
        trigger.setAccountWalkDelayDays(3);
        trigger.setBot(bot(0));
        trigger.setAccountWalkDelayBotId(trigger.getBot().getId());
        trigger.setAccountWalkNotBefore(LocalDate.of(2026, 7, 19));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS, 2)).thenReturn(2);

        service.synchronizeAfterAccountChange(trigger);

        assertFalse(trigger.isVigul());
        assertEquals(3, trigger.getAccountWalkDelayDays());
        assertEquals(LocalDate.of(2026, 7, 19), trigger.getPublishedDate());
        verify(reviewRepository, never()).findAllByOrderIdForAccountWalkSchedule(500L);
        verify(reviewRepository, never()).saveAll(anyList());
    }

    @Test
    void anotherUnwalkedAccountOnSameDayDoesNotStackAnotherDelay() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(600L);
        Review trigger = review(50L, details, LocalDate.of(2026, 7, 19), false);
        trigger.setBot(bot(1));
        trigger.setAccountWalkDelayBotId(999L);
        trigger.setAccountWalkNotBefore(LocalDate.of(2026, 7, 19));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS, 2)).thenReturn(2);

        service.synchronizeAfterAccountChange(trigger);

        assertEquals(LocalDate.of(2026, 7, 19), trigger.getPublishedDate());
        assertEquals(trigger.getBot().getId(), trigger.getAccountWalkDelayBotId());
        assertEquals(LocalDate.of(2026, 7, 19), trigger.getAccountWalkNotBefore());
        verify(reviewRepository, never()).saveAll(anyList());
    }

    @Test
    void laterReplacementOnlyAddsMissingTimeForNewAccount() {
        Clock nextDayClock = Clock.fixed(Instant.parse("2026-07-21T04:00:00Z"), BUSINESS_ZONE);
        ReviewAccountWalkScheduleService service = new ReviewAccountWalkScheduleService(
                reviewRepository,
                appSettingService,
                businessAuditService,
                nextDayClock
        );
        OrderDetails details = details(700L);
        Review trigger = review(60L, details, LocalDate.of(2026, 7, 22), false);
        trigger.setBot(bot(0));
        trigger.setAccountWalkDelayBotId(999L);
        trigger.setAccountWalkNotBefore(LocalDate.of(2026, 7, 22));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS, 2)).thenReturn(2);
        when(reviewRepository.findAllByOrderIdForAccountWalkSchedule(700L)).thenReturn(List.of(trigger));

        service.synchronizeAfterAccountChange(trigger);

        assertEquals(LocalDate.of(2026, 7, 23), trigger.getPublishedDate());
        assertEquals(1, trigger.getAccountWalkDelayDays());
        assertEquals(LocalDate.of(2026, 7, 23), trigger.getAccountWalkNotBefore());
        verify(reviewRepository).saveAll(List.of(trigger));
    }

    @Test
    void manualDateGuardUsesStoredWindowInsteadOfLegacyAccumulatedDays() {
        ReviewAccountWalkScheduleService service = service();
        Review review = review(70L, details(800L), LocalDate.of(2026, 7, 19), false);
        review.setBot(bot(0));
        review.setAccountWalkDelayDays(10);
        review.setAccountWalkDelayBotId(review.getBot().getId());
        review.setAccountWalkNotBefore(LocalDate.of(2026, 7, 19));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 2)).thenReturn(3);

        assertEquals(LocalDate.of(2026, 7, 19), service.minimumPublicationDateForCurrentAccount(review));
    }

    private ReviewAccountWalkScheduleService service() {
        return new ReviewAccountWalkScheduleService(reviewRepository, appSettingService, businessAuditService, CLOCK);
    }

    private Review review(Long id, OrderDetails details, LocalDate publishedDate, boolean publish) {
        Review review = new Review();
        review.setId(id);
        review.setOrderDetails(details);
        review.setPublishedDate(publishedDate);
        review.setPublish(publish);
        return review;
    }

    private OrderDetails details(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);
        return details;
    }

    private Bot bot(int counter) {
        Bot bot = new Bot();
        bot.setId((long) counter + 10);
        bot.setFio("Тестовый Аккаунт " + counter);
        bot.setLogin("bot" + counter);
        bot.setPassword("secret");
        bot.setActive(true);
        bot.setCounter(counter);
        return bot;
    }

    private LocalDate futureBusinessDate(int daysAhead) {
        LocalDate date = LocalDate.now(BUSINESS_ZONE).plusDays(daysAhead);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.plusDays(2).getDayOfWeek() == DayOfWeek.SATURDAY) {
            date = date.plusDays(1);
        }
        return date;
    }
}
