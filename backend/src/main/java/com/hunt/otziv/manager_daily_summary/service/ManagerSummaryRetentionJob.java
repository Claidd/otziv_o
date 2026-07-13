package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.manager_daily_summary.repository.ManagerPerformanceDailyRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSiteActivityEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSummaryDeliveryLogRepository;
import com.hunt.otziv.manager_control.repository.ManagerQueueStateEventRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerSummaryRetentionJob {

    private final AppSettingService appSettingService;
    private final ManagerSiteActivityEventRepository activityRepository;
    private final ManagerSummaryDeliveryLogRepository deliveryRepository;
    private final ManagerPerformanceDailyRepository dailyRepository;
    private final ClientChatMessageRepository messageRepository;
    private final ClientChatUnansweredItemRepository unansweredRepository;
    private final ManagerQueueStateEventRepository queueStateEventRepository;

    @Scheduled(cron = "${manager.summary.cleanup-cron:0 45 3 * * *}", zone = "${manager.summary.zone:Asia/Irkutsk}")
    @Transactional
    public void cleanup() {
        if (!appSettingService.getBoolean("manager.summary.cleanup-enabled", false)) {
            return;
        }
        int rawDays = Math.max(30, appSettingService.getInt("manager.summary.raw-retention-days", 90));
        int textDays = Math.max(7, appSettingService.getInt("manager.summary.message-text-retention-days", 30));
        int deliveryDays = Math.max(30, appSettingService.getInt("manager.summary.delivery-retention-days", 180));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime rawCutoff = now.minusDays(rawDays);
        var oldestAggregate = dailyRepository.findTopByOrderBySummaryDateAsc();
        if (oldestAggregate.isEmpty() || oldestAggregate.get().getSummaryDate().isAfter(rawCutoff.toLocalDate())) {
            log.warn("Manager summary retention skipped: verified daily history does not cover the retention cutoff");
            return;
        }

        int anonymized = messageRepository.anonymizeTextBefore(now.minusDays(textDays));
        long deletedClosedWaits = unansweredRepository.deleteByStatusNotAndClosedAtBefore(
                ClientChatUnansweredStatus.OPEN,
                now.minusDays(rawDays)
        );
        int deletedMessages = 0;
        int batch;
        do {
            batch = messageRepository.deleteUnreferencedBatchBefore(rawCutoff);
            deletedMessages += batch;
        } while (batch == 5000);
        long deletedActivity = activityRepository.deleteByCreatedAtBefore(rawCutoff);
        long deletedQueueStates = queueStateEventRepository.deleteByCreatedAtBefore(rawCutoff);
        long deletedDelivery = deliveryRepository.deleteByCreatedAtBefore(now.minusDays(deliveryDays));
        log.info(
                "Manager summary retention complete: anonymizedMessages={}, deletedMessages={}, deletedClosedWaits={}, deletedActivity={}, deletedQueueStates={}, deletedDelivery={}",
                anonymized, deletedMessages, deletedClosedWaits, deletedActivity, deletedQueueStates, deletedDelivery
        );
    }
}
