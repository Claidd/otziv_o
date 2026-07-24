package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlWorkerReminderScheduler {

    private static final int BATCH_SIZE = 100;
    private static final int REMINDER_INTERVAL_HOURS = 3;

    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final ManagerControlWorkerTaskTelegramCallbackService callbackService;

    @Scheduled(
            fixedDelayString = "${manager.worker-explanation-reminders.fixed-delay-ms:300000}",
            initialDelayString = "${manager.worker-explanation-reminders.initial-delay-ms:60000}"
    )
    public void remindPendingExplanations() {
        LocalDateTime now = LocalDateTime.now();
        List<ManagerDailyControlConcreteItem> pending = concreteItemRepository
                .findPendingWorkerExplanationReminders(
                        now.minusHours(REMINDER_INTERVAL_HOURS),
                        PageRequest.of(0, BATCH_SIZE)
                );
        for (ManagerDailyControlConcreteItem item : pending) {
            try {
                callbackService.remindPendingExplanation(item, now);
            } catch (RuntimeException exception) {
                log.warn("Не удалось повторно напомнить специалисту по карточке {}: {}",
                        item == null ? null : item.getId(), exception.getMessage());
            }
        }
    }
}
