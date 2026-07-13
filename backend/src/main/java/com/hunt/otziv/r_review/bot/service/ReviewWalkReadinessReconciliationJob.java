package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReviewWalkReadinessReconciliationJob {

    private final ReviewRepository reviewRepository;
    private final BotAssignmentService botAssignmentService;

    @Scheduled(
            cron = "${app.review.walk-readiness-reconciliation-cron:0 20 3 * * *}",
            zone = "Asia/Irkutsk"
    )
    public void reconcile() {
        try {
            int promoted = botAssignmentService.promoteReviewsWithWalkedAccounts(
                    reviewRepository.findByPublishFalseAndBotIsNotNull()
            );
            if (promoted > 0) {
                log.warn("Исправлено несогласованных состояний готовности отзывов: {}", promoted);
            }
        } catch (Exception e) {
            log.error("Не удалось выполнить ежедневную сверку готовности аккаунтов", e);
        }
    }
}
