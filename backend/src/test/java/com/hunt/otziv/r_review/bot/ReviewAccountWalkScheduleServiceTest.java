package com.hunt.otziv.r_review.bot;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.time.DayOfWeek;
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

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private AppSettingService appSettingService;

    @Test
    void walkedToUnwalkedShiftsOnlyFollowingReviewsThatBecomeTooClose() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(100L);
        Review previous = review(1L, details, LocalDate.of(2026, 5, 30), false);
        Review trigger = review(2L, details, LocalDate.of(2026, 6, 1), false);
        Review closeFollowing = review(3L, details, LocalDate.of(2026, 6, 3), false);
        Review farFollowing = review(4L, details, LocalDate.of(2026, 6, 8), false);
        trigger.setBot(bot(1));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 3)).thenReturn(3);
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS, 2)).thenReturn(2);
        when(reviewRepository.findAllByOrderIdForAccountWalkSchedule(100L))
                .thenReturn(List.of(previous, trigger, closeFollowing, farFollowing));

        service.synchronizeAfterAccountChange(trigger, true);

        assertFalse(trigger.isVigul());
        assertEquals(2, trigger.getAccountWalkDelayDays());
        assertEquals(2, closeFollowing.getAccountWalkDelayDays());
        assertEquals(0, farFollowing.getAccountWalkDelayDays());
        assertEquals(LocalDate.of(2026, 5, 30), previous.getPublishedDate());
        assertEquals(LocalDate.of(2026, 6, 3), trigger.getPublishedDate());
        assertEquals(LocalDate.of(2026, 6, 5), closeFollowing.getPublishedDate());
        assertEquals(LocalDate.of(2026, 6, 8), farFollowing.getPublishedDate());
        verify(reviewRepository).saveAll(List.of(previous, trigger, closeFollowing, farFollowing));
    }

    @Test
    void unwalkedToWalkedRemovesStoredDelayWhereSpacingAllows() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(200L);
        LocalDate restoredTriggerDate = futureBusinessDate(10);
        LocalDate restoredFollowingDate = restoredTriggerDate.plusDays(2);
        Review trigger = review(10L, details, restoredTriggerDate.plusDays(2), false);
        Review following = review(11L, details, restoredFollowingDate.plusDays(2), false);
        Review untouched = review(12L, details, restoredFollowingDate.plusDays(5), false);
        trigger.setAccountWalkDelayDays(2);
        following.setAccountWalkDelayDays(2);
        trigger.setBot(bot(3));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 3)).thenReturn(3);
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS, 2)).thenReturn(2);
        when(reviewRepository.findAllByOrderIdForAccountWalkSchedule(200L))
                .thenReturn(List.of(trigger, following, untouched));

        service.synchronizeAfterAccountChange(trigger, false);

        assertTrue(trigger.isVigul());
        assertEquals(0, trigger.getAccountWalkDelayDays());
        assertEquals(0, following.getAccountWalkDelayDays());
        assertEquals(0, untouched.getAccountWalkDelayDays());
        assertEquals(restoredTriggerDate, trigger.getPublishedDate());
        assertEquals(restoredFollowingDate, following.getPublishedDate());
        assertEquals(restoredFollowingDate.plusDays(5), untouched.getPublishedDate());
        verify(reviewRepository).saveAll(List.of(trigger, following, untouched));
    }

    @Test
    void unwalkedToWalkedDoesNotRestorePublicationDateIntoPast() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(250L);
        LocalDate currentFutureDate = LocalDate.now().plusDays(1);
        Review trigger = review(15L, details, currentFutureDate, false);
        trigger.setAccountWalkDelayDays(2);
        trigger.setBot(bot(6));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 3)).thenReturn(3);
        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS, 2)).thenReturn(2);
        when(reviewRepository.findAllByOrderIdForAccountWalkSchedule(250L))
                .thenReturn(List.of(trigger));

        service.synchronizeAfterAccountChange(trigger, false);

        assertTrue(trigger.isVigul());
        assertEquals(0, trigger.getAccountWalkDelayDays());
        assertEquals(currentFutureDate, trigger.getPublishedDate());
        verify(reviewRepository).saveAll(List.of(trigger));
    }

    @Test
    void sameWalkStateDoesNotShiftDates() {
        ReviewAccountWalkScheduleService service = service();
        OrderDetails details = details(300L);
        Review trigger = review(20L, details, LocalDate.of(2026, 6, 8), false);
        trigger.setBot(bot(4));

        when(appSettingService.getInt(AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD, 3)).thenReturn(3);

        service.synchronizeAfterAccountChange(trigger, true);

        assertTrue(trigger.isVigul());
        assertEquals(0, trigger.getAccountWalkDelayDays());
        assertEquals(LocalDate.of(2026, 6, 8), trigger.getPublishedDate());
        verify(reviewRepository, never()).findAllByOrderIdForAccountWalkSchedule(300L);
        verify(reviewRepository, never()).saveAll(anyList());
    }

    private ReviewAccountWalkScheduleService service() {
        return new ReviewAccountWalkScheduleService(reviewRepository, appSettingService);
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
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            date = date.plusDays(1);
        }
        return date;
    }
}
