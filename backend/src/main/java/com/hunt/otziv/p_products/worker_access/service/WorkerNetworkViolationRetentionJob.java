package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import com.hunt.otziv.p_products.worker_access.repository.WorkerNetworkViolationRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerNetworkViolationRetentionJob {
    private static final ZoneId WORKER_ZONE = ZoneId.of("Asia/Irkutsk");

    private final WorkerCellularAccessProperties properties;
    private final WorkerNetworkViolationRepository violationRepository;

    @Scheduled(cron = "${otziv.worker.cellular-access.violation-cleanup-cron:0 50 3 * * *}", zone = "Asia/Irkutsk")
    public void cleanup() {
        if (!properties.isViolationStatisticsEnabled()) {
            return;
        }
        int retentionDays = Math.max(30, properties.getViolationRetentionDays());
        LocalDateTime cutoff = LocalDateTime.now(WORKER_ZONE).minusDays(retentionDays);
        try {
            int deleted = violationRepository.deleteBefore(cutoff);
            if (deleted > 0) {
                log.info("Удалены устаревшие эпизоды нарушений сети специалистов: count={}, cutoff={}", deleted, cutoff);
            }
        } catch (RuntimeException exception) {
            log.warn("Не удалось удалить устаревшие нарушения сети: {}", exception.getClass().getSimpleName());
        }
    }
}
