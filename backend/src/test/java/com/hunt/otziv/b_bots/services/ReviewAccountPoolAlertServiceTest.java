package com.hunt.otziv.b_bots.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.b_bots.model.ReviewAccountPoolAlertState;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.repository.ReviewAccountPoolAlertStateRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewAccountPoolAlertServiceTest {

    @Mock
    private ReviewAccountPoolAlertStateRepository stateRepository;
    @Mock
    private BotsRepository botsRepository;
    @Mock
    private PersonalReminderService personalReminderService;
    @Mock
    private UserService userService;
    @Mock
    private TelegramService telegramService;

    @Test
    void notifiesOwnersAndAdminsOnceWhenPoolReachesFifty() {
        ReviewAccountPoolAlertState state = state(51, 0, 0);
        User owner = user(10L, 100L);
        User admin = user(11L, null);
        stub(state, 50, List.of(owner), List.of(admin));

        int remaining = service().reconcileAndNotify();

        assertEquals(50, remaining);
        assertEquals(50, state.getLastRemainingCount());
        assertEquals(1, state.getNotifiedThresholdMask());
        verify(personalReminderService).createSystemReminderDueNow(
                eq(owner), anyString(), anyString(), eq(ReviewAccountPoolAlertService.SOURCE_TYPE), anyLong(), eq(null)
        );
        verify(personalReminderService).createSystemReminderDueNow(
                eq(admin), anyString(), anyString(), eq(ReviewAccountPoolAlertService.SOURCE_TYPE), anyLong(), eq(null)
        );
        verify(telegramService).sendMessage(eq(100L), anyString());
    }

    @Test
    void doesNotRepeatAlreadySentThreshold() {
        ReviewAccountPoolAlertState state = state(50, 1, 0);
        stub(state, 50, List.of(user(10L, 100L)), List.of());

        service().reconcileAndNotify();

        verify(personalReminderService, never()).createSystemReminderDueNow(
                any(), anyString(), anyString(), anyString(), anyLong(), any()
        );
        verify(telegramService, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void replenishmentStartsNewCycleAndAllowsThresholdAgain() {
        ReviewAccountPoolAlertState state = state(0, 63, 2);
        stub(state, 50, List.of(user(10L, null)), List.of());

        service().reconcileAndNotify();

        assertEquals(3, state.getCycleNumber());
        assertEquals(1, state.getNotifiedThresholdMask());
        verify(personalReminderService).createSystemReminderDueNow(
                any(), anyString(), anyString(), eq(ReviewAccountPoolAlertService.SOURCE_TYPE), anyLong(), eq(null)
        );
    }

    @Test
    void firstStartupAtZeroSendsZeroAlert() {
        ReviewAccountPoolAlertState state = state(null, 0, 0);
        stub(state, 0, List.of(user(10L, null)), List.of());

        service().reconcileAndNotify();

        assertEquals(32, state.getNotifiedThresholdMask());
        verify(personalReminderService).createSystemReminderDueNow(
                any(), anyString(), anyString(), eq(ReviewAccountPoolAlertService.SOURCE_TYPE), anyLong(), eq(null)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 40, 30, 20, 10, 0})
    void recognizesEveryConfiguredThreshold(int threshold) {
        ReviewAccountPoolAlertState state = state(threshold + 1, 0, 0);
        stub(state, threshold, List.of(), List.of());

        service().reconcileAndNotify();

        int expectedBit = 1 << ReviewAccountPoolAlertService.ALERT_THRESHOLDS.indexOf(threshold);
        assertEquals(expectedBit, state.getNotifiedThresholdMask());
        assertEquals(threshold, state.getLastRemainingCount());
    }

    private void stub(ReviewAccountPoolAlertState state, long count, List<User> owners, List<User> admins) {
        when(stateRepository.findByIdForUpdate(ReviewAccountPoolAlertService.STATE_ID)).thenReturn(Optional.of(state));
        when(botsRepository.countAvailableAccountPool(
                anyLong(), anyString(), anyString(), anyInt(), anyInt(), any(LocalDate.class)
        )).thenReturn(count);
        lenient().when(botsRepository.countUnpublishedStubReviews()).thenReturn(0L);
        lenient().when(userService.getAllOwners("ROLE_OWNER")).thenReturn(owners);
        lenient().when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(admins);
    }

    private ReviewAccountPoolAlertService service() {
        return new ReviewAccountPoolAlertService(
                stateRepository,
                botsRepository,
                personalReminderService,
                userService,
                telegramService
        );
    }

    private ReviewAccountPoolAlertState state(Integer remaining, int mask, long cycle) {
        ReviewAccountPoolAlertState state = new ReviewAccountPoolAlertState();
        state.setId(ReviewAccountPoolAlertService.STATE_ID);
        state.setLastRemainingCount(remaining);
        state.setNotifiedThresholdMask(mask);
        state.setCycleNumber(cycle);
        return state;
    }

    private User user(Long id, Long chatId) {
        User user = new User();
        user.setId(id);
        user.setActive(true);
        user.setTelegramChatId(chatId);
        return user;
    }
}
