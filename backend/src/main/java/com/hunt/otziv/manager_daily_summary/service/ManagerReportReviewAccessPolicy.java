package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class ManagerReportReviewAccessPolicy {

    private static final long CACHE_SECONDS = 5;

    private final ManagerReportReviewSessionRepository repository;
    private final AppSettingService appSettingService;
    private final ManagerReportReviewQualityService qualityService;
    private final Map<Long, CachedState> cache = new ConcurrentHashMap<>();

    public ManagerReportReviewAccessPolicy(
            ManagerReportReviewSessionRepository repository,
            AppSettingService appSettingService,
            ManagerReportReviewQualityService qualityService
    ) {
        this.repository = repository;
        this.appSettingService = appSettingService;
        this.qualityService = qualityService;
    }

    public AccessState state(User user) {
        // The callers are already protected by ROLE_MANAGER. Avoid reading the
        // lazy roles collection here because the filter executes outside a JPA transaction.
        if (user == null || user.getId() == null || !user.isActive()) {
            return AccessState.allowed();
        }
        if (!appSettingService.getBoolean("manager.report-review.enabled", true)) {
            return AccessState.allowed();
        }
        LocalDateTime now = LocalDateTime.now();
        CachedState cached = cache.get(user.getId());
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.state();
        }
        int deadlineMinutes = Math.max(30, Math.min(1440, appSettingService.getInt(
                "manager.report-review.reminder-three-minutes",
                180
        )));
        boolean restrictionEnabled =
                appSettingService.getBoolean("manager.report-review.restriction-enabled", true);
        boolean aiAvailable = qualityService.aiAvailable();
        Optional<ManagerReportReviewSession> overdue = restrictionEnabled && aiAvailable
                ? repository.findFirstByManagerUserIdAndTestModeFalseAndDeadlineStartedAtLessThanEqualAndAiUnavailableStartedAtIsNullAndStatusNotInAndCompletedAtIsNullOrderByDeadlineStartedAtAsc(
                        user.getId(),
                        now.minusMinutes(deadlineMinutes),
                        java.util.List.of(
                                ManagerReportReviewStatus.COMPLETED,
                                ManagerReportReviewStatus.DISPUTED
                        )
                )
                : Optional.empty();
        Optional<ManagerReportReviewSession> pending = overdue.isPresent()
                ? overdue
                : repository.findFirstByManagerUserIdAndTestModeFalseAndCompletedAtIsNullOrderByCreatedAtDesc(
                        user.getId()
                );
        AccessState state = pending
                .map(review -> new AccessState(
                        true,
                        overdue.isPresent(),
                        review.getId(),
                        review.getSummaryDate(),
                        review.getDeadlineStartedAt() == null
                                ? null
                                : review.getDeadlineStartedAt().plusMinutes(deadlineMinutes),
                        review.getStatus() == null ? "" : review.getStatus().name(),
                        Math.max(0, review.getIssueCount()),
                        Math.max(0, Math.min(review.getCurrentQuestionIndex(), review.getIssueCount())),
                        review.getStartedAt(),
                        review.getReadingConfirmedAt(),
                        overdue.isPresent()
                                ? "Завершите проверку персонального отчёта в Telegram. "
                                + "После правильных ответов доступ восстановится автоматически."
                                : pendingMessage(review)
                ))
                .orElseGet(AccessState::allowed);
        cache.put(user.getId(), new CachedState(state, now.plusSeconds(CACHE_SECONDS)));
        return state;
    }

    public void invalidate(Long userId) {
        if (userId != null) cache.remove(userId);
    }

    private record CachedState(AccessState state, LocalDateTime expiresAt) {
    }

    private String pendingMessage(ManagerReportReviewSession review) {
        if (review.getStatus() == ManagerReportReviewStatus.READING) {
            return "Изучите раскрытый отчёт в Telegram и подтвердите прочтение.";
        }
        if (review.getStatus() == ManagerReportReviewStatus.QUESTION_PENDING) {
            return "Продолжите проверку в Telegram: отвечайте на вопросы по тексту отчёта.";
        }
        if (review.getStatus() == ManagerReportReviewStatus.DISPUTED) {
            return "Спор по отчёту передан владельцу и ожидает решения.";
        }
        return "Откройте Telegram и нажмите «Изучить отчёт».";
    }

    public record AccessState(
            boolean pending,
            boolean restricted,
            Long reviewId,
            java.time.LocalDate summaryDate,
            LocalDateTime restrictedFrom,
            String reviewStatus,
            int questionCount,
            int answeredQuestionCount,
            LocalDateTime readingStartedAt,
            LocalDateTime readingConfirmedAt,
            String message
    ) {
        static AccessState allowed() {
            return new AccessState(false, false, null, null, null, "", 0, 0, null, null, "");
        }
    }
}
