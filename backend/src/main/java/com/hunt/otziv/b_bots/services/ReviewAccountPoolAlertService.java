package com.hunt.otziv.b_bots.services;

import com.hunt.otziv.b_bots.model.ReviewAccountPoolAlertState;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.repository.ReviewAccountPoolAlertStateRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewAccountPoolAlertService {

    static final int STATE_ID = 1;
    static final List<Integer> ALERT_THRESHOLDS = List.of(50, 40, 30, 20, 10, 0);
    static final String SOURCE_TYPE = "REVIEW_ACCOUNT_POOL_LOW";
    static final String LOW_UNBLOCKED_SOURCE_TYPE = "REVIEW_ACCOUNT_CITY_LOW";
    private static final long POOL_CITY_ID = 325L;
    private static final int LOW_UNBLOCKED_THRESHOLD = 100;
    private static final String POOL_ACCOUNT_NAME = "Впиши Имя Фамилию";
    private static final String READY_STATUS = "Новый";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final int SHORTAGE_REMINDER_HOURS = 6;

    private final ReviewAccountPoolAlertStateRepository stateRepository;
    private final BotsRepository botsRepository;
    private final PersonalReminderService personalReminderService;
    private final UserService userService;
    private final TelegramService telegramService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcileAndNotify() {
        ReviewAccountPoolAlertState state = stateRepository.findByIdForUpdate(STATE_ID)
                .orElseGet(this::newState);
        int remaining = Math.toIntExact(botsRepository.countAvailableAccountPool(
                POOL_CITY_ID,
                POOL_ACCOUNT_NAME,
                READY_STATUS,
                0,
                1,
                LocalDate.now(BUSINESS_ZONE)
        ));
        int required = Math.toIntExact(botsRepository.countUnpublishedStubReviews());
        int unblocked = Math.toIntExact(botsRepository.countActiveByCityId(POOL_CITY_ID));
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        Integer previous = state.getLastRemainingCount();
        int previousRequired = state.getLastRequiredCount();
        boolean replenished = previous != null && remaining > previous;
        if (replenished) {
            state.setCycleNumber(state.getCycleNumber() + 1);
            state.setNotifiedThresholdMask(0);
            log.info("Пул аккаунтов пополнен: {} -> {}, новый цикл {}",
                    previous, remaining, state.getCycleNumber());
        }

        List<Integer> reached = reachedThresholds(previous, remaining, replenished, state.getNotifiedThresholdMask());
        int mask = state.getNotifiedThresholdMask();
        for (Integer threshold : reached) {
            mask |= thresholdBit(threshold);
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        boolean shortage = required > remaining;
        boolean shortageStarted = shortage && (previous == null || previousRequired <= previous);
        boolean reminderDue = shortage && (state.getLastNotifiedAt() == null
                || !now.isBefore(state.getLastNotifiedAt().plusHours(SHORTAGE_REMINDER_HOURS)));
        boolean shouldNotify = !reached.isEmpty() || shortageStarted || reminderDue;
        boolean shouldNotifyLowUnblocked = unblocked < LOW_UNBLOCKED_THRESHOLD
                && !today.equals(state.getLastLowUnblockedNotifiedOn());

        state.setNotifiedThresholdMask(mask);
        state.setLastRemainingCount(remaining);
        state.setLastRequiredCount(required);
        if (shouldNotify) {
            state.setLastNotifiedAt(now);
        }
        if (shouldNotifyLowUnblocked) {
            state.setLastLowUnblockedNotifiedOn(today);
        }
        stateRepository.save(state);

        long cycleNumber = state.getCycleNumber();
        Integer reachedThreshold = reached.isEmpty() ? null : reached.get(reached.size() - 1);
        notifyAfterCommit(shouldNotify, reachedThreshold, remaining, required, cycleNumber, now);
        notifyLowUnblockedAfterCommit(shouldNotifyLowUnblocked, unblocked, today);
        return remaining;
    }

    private void notifyAfterCommit(
            boolean shouldNotify,
            Integer threshold,
            int remaining,
            int required,
            long cycleNumber,
            LocalDateTime notifiedAt
    ) {
        if (!shouldNotify) {
            return;
        }
        Runnable notification = () -> notifyRecipients(threshold, remaining, required, cycleNumber, notifiedAt);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notification.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notification.run();
            }
        });
    }

    private void notifyLowUnblockedAfterCommit(boolean shouldNotify, int unblocked, LocalDate notifiedOn) {
        if (!shouldNotify) {
            return;
        }
        Runnable notification = () -> notifyLowUnblockedRecipients(unblocked, notifiedOn);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notification.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notification.run();
            }
        });
    }

    private List<Integer> reachedThresholds(
            Integer previous,
            int remaining,
            boolean replenished,
            int notifiedMask
    ) {
        List<Integer> result = new ArrayList<>();
        for (Integer threshold : ALERT_THRESHOLDS) {
            if ((notifiedMask & thresholdBit(threshold)) != 0) {
                continue;
            }
            boolean reachedOnFirstCheck = previous == null && remaining == threshold;
            boolean reachedAfterReplenishment = replenished && remaining == threshold;
            boolean crossedDown = previous != null
                    && !replenished
                    && previous > threshold
                    && remaining <= threshold;
            if (reachedOnFirstCheck || reachedAfterReplenishment || crossedDown) {
                result.add(threshold);
            }
        }
        return result;
    }

    private int thresholdBit(int threshold) {
        int index = ALERT_THRESHOLDS.indexOf(threshold);
        return index < 0 ? 0 : 1 << index;
    }

    private void notifyRecipients(
            Integer threshold,
            int remaining,
            int required,
            long cycleNumber,
            LocalDateTime notifiedAt
    ) {
        String title = remaining == 0
                ? "Аккаунты для выгула закончились"
                : "Заканчиваются аккаунты для выгула";
        int deficit = Math.max(0, required - remaining);
        int coverage = required <= 0 ? 100 : Math.min(100, (int) Math.round(remaining * 100.0d / required));
        String text = "В общем пуле осталось аккаунтов: " + remaining + "."
                + (threshold == null ? "" : "\nДостигнут порог: " + threshold + ".")
                + "\nПубликаций с ботом-заглушкой: " + required + "."
                + "\nТекущий дефицит: " + deficit + "."
                + "\nПокрытие потребности: " + coverage + "%."
                + "\nПул: город 325, активные аккаунты «Впиши Имя Фамилию» со счетчиком 0–1."
                + "\n\nНеобходимо пополнить пул.";
        long sourceId = (cycleNumber + 1) * 10_000_000L
                + notifiedAt.atZone(BUSINESS_ZONE).toEpochSecond() / 3600L;

        recipients().values().forEach(user -> notifyUser(user, title, text, SOURCE_TYPE, sourceId));
        log.warn("Отправлено уведомление о пуле аккаунтов: threshold={}, remaining={}, required={}, deficit={}, cycle={}",
                threshold, remaining, required, deficit, cycleNumber);
    }

    private void notifyLowUnblockedRecipients(int unblocked, LocalDate notifiedOn) {
        String title = "В городе 325 заканчиваются аккаунты";
        String text = "Незаблокированных аккаунтов в городе 325 осталось: " + unblocked + "."
                + "\nПорог ежедневного уведомления: меньше " + LOW_UNBLOCKED_THRESHOLD + "."
                + "\n\nНеобходимо добавить аккаунты.";
        long sourceId = notifiedOn.toEpochDay();

        recipients().values().forEach(user ->
                notifyUser(user, title, text, LOW_UNBLOCKED_SOURCE_TYPE, sourceId));
        log.warn("Отправлено ежедневное уведомление о незаблокированных аккаунтах: cityId={}, count={}",
                POOL_CITY_ID, unblocked);
    }

    private Map<Long, User> recipients() {
        Map<Long, User> result = new LinkedHashMap<>();
        addRecipients(result, userService.getAllOwners("ROLE_OWNER"));
        addRecipients(result, userService.getAllOwners("ROLE_ADMIN"));
        return result;
    }

    private void addRecipients(Map<Long, User> recipients, List<User> users) {
        if (users == null) {
            return;
        }
        users.stream()
                .filter(user -> user != null && user.getId() != null && user.isActive())
                .forEach(user -> recipients.putIfAbsent(user.getId(), user));
    }

    private void notifyUser(
            User user,
            String title,
            String text,
            String sourceType,
            long sourceId
    ) {
        try {
            personalReminderService.createSystemReminderDueNow(
                    user,
                    title,
                    text,
                    sourceType,
                    sourceId,
                    null
            );
        } catch (RuntimeException e) {
            log.warn("Не удалось создать напоминание о пуле для userId={}", user.getId(), e);
        }

        if (user.getTelegramChatId() == null) {
            return;
        }
        try {
            telegramService.sendMessage(user.getTelegramChatId(), title + "\n\n" + text);
        } catch (RuntimeException e) {
            log.warn("Не удалось отправить Telegram-уведомление о пуле для userId={}", user.getId(), e);
        }
    }

    private ReviewAccountPoolAlertState newState() {
        ReviewAccountPoolAlertState state = new ReviewAccountPoolAlertState();
        state.setId(STATE_ID);
        return state;
    }
}
