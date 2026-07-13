package com.hunt.otziv.r_review.bot.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewBotAssignmentExclusionCleanupJob {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");

    private final ReviewBotAssignmentExclusionService exclusionService;

    @Value("${app.review.bot-assignment-exclusion-retention-days:7}")
    private int retentionDays;

    @Scheduled(
            cron = "${app.review.bot-assignment-exclusion-cleanup-cron:0 35 3 * * *}",
            zone = "Asia/Irkutsk"
    )
    public void cleanup() {
        try {
            LocalDateTime cutoff = LocalDateTime.now(BUSINESS_ZONE).minusDays(Math.max(1, retentionDays));
            int deleted = exclusionService.clearPublishedBefore(cutoff);
            if (deleted > 0) {
                log.info("Удалено устаревших исключений аккаунтов опубликованных отзывов: {}", deleted);
            }
        } catch (RuntimeException exception) {
            log.error("Не удалось очистить устаревшие исключения аккаунтов отзывов", exception);
        }
    }
}
