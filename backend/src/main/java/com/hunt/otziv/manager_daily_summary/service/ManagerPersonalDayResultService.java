package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.worker_performance.service.EndOfDayAchievementService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerPersonalDayResultService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final ManagerRepository managerRepository;
    private final EndOfDayAchievementService achievementService;
    private final ManagerReportReviewTelegramService reportReviewService;

    /** Field-injected so existing constructors and tests stay unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * One short transaction per manager instead of one for everybody: the delivery calls an AI
     * service and Telegram, so a single transaction held connections and row locks for minutes,
     * and a failure on the k-th manager rolled back results already sent to the others.
     */
    public int send(LocalDate date, List<ManagerDailySummaryResponse> summaries) {
        if (date == null || summaries == null || summaries.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (ManagerDailySummaryResponse summary : summaries) {
            if (summary == null || summary.managerId() == null || !date.equals(summary.date())) {
                continue;
            }
            try {
                Boolean delivered = inOwnTransaction(() -> sendOne(date, summary));
                if (Boolean.TRUE.equals(delivered)) {
                    sent++;
                }
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(ManagerPersonalDayResultService.class)
                        .warn("Итог дня менеджера {} не отправлен", summary.managerId(), failure);
            }
        }
        return sent;
    }

    private <T> T inOwnTransaction(java.util.function.Supplier<T> work) {
        if (transactionManager == null) {
            return work.get();
        }
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private boolean sendOne(LocalDate date, ManagerDailySummaryResponse summary) {
        Manager manager = managerRepository.findByIdWithUser(summary.managerId()).orElse(null);
        if (manager == null) {
            return false;
        }
        {
            boolean reached100 = summary.taskTotal() > 0
                    && summary.taskOpen() <= 0
                    && summary.taskProgressPercent() != null
                    && summary.taskProgressPercent().compareTo(ONE_HUNDRED) >= 0;
            EndOfDayAchievementService.AchievementResult result = achievementService.saveResult(
                    date,
                    EndOfDayAchievementService.ROLE_MANAGER_WORKDAY,
                    summary.managerId(),
                    summary.managerUserId(),
                    summary.taskTotal(),
                    summary.taskCompleted() + summary.taskAutoClosed(),
                    percent(summary.taskProgressPercent()),
                    0,
                    reached100
            );
            if (reportReviewService != null && reportReviewService.enabled()) {
                if (reportReviewService.deliver(manager, summary)) {
                    achievementService.markManagerWorkdayNotified(result);
                    return true;
                }
                return false;
            }
            return achievementService.notifyManagerWorkday(manager, result, summary.confirmedActiveSeconds());
        }
    }

    private double percent(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }
}
