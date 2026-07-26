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

    @Transactional
    public int send(LocalDate date, List<ManagerDailySummaryResponse> summaries) {
        if (date == null || summaries == null || summaries.isEmpty()) {
            return 0;
        }
        Map<Long, Manager> managers = managerRepository.findAllWithUserAndImage().stream()
                .filter(manager -> manager.getId() != null)
                .collect(Collectors.toMap(Manager::getId, Function.identity(), (left, right) -> left));
        int sent = 0;
        for (ManagerDailySummaryResponse summary : summaries) {
            if (summary == null || summary.managerId() == null || !date.equals(summary.date())) {
                continue;
            }
            Manager manager = managers.get(summary.managerId());
            if (manager == null) {
                continue;
            }
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
                    sent++;
                }
            } else if (achievementService.notifyManagerWorkday(manager, result, summary.confirmedActiveSeconds())) {
                sent++;
            }
        }
        return sent;
    }

    private double percent(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }
}
