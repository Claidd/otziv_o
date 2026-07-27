package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewManagerSettingResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewSettingsRequest;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewSettingsResponse;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewIssueRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewSettingsService {

    private static final String PREFIX = "manager.report-review.";
    private static final String MANAGER_GROUPS_ENABLED = "manager.summary.manager-groups-enabled";

    private final AppSettingService settings;
    private final ManagerRepository managerRepository;
    private final ManagerReportReviewSessionRepository sessionRepository;
    private final ManagerReportReviewIssueRepository issueRepository;
    private final ManagerReportReviewEventRepository eventRepository;
    private final ManagerReportReviewAccessPolicy accessPolicy;

    @Transactional(readOnly = true)
    public ManagerReportReviewSettingsResponse settings() {
        return response();
    }

    @Transactional
    public ManagerReportReviewSettingsResponse update(ManagerReportReviewSettingsRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Настройки аудита не переданы");
        }
        validate(request);
        settings.setBoolean(PREFIX + "enabled", request.enabled());
        settings.setBoolean(MANAGER_GROUPS_ENABLED, request.managerGroupsEnabled());
        settings.setBoolean(PREFIX + "restriction-enabled", request.restrictionEnabled());
        saveInt("max-question-count", request.maxQuestionCount(), 1, 12, "Максимум вопросов");
        saveInt("minimum-read-seconds", request.minimumReadSeconds(), 30, 300, "Время чтения");
        saveInt("test-minimum-read-seconds", request.testMinimumReadSeconds(), 3, 30, "Время чтения в тесте");
        saveInt("reminder-one-minutes", request.reminderOneMinutes(), 5, 1440, "Первое напоминание");
        saveInt("reminder-three-minutes", request.reminderThreeMinutes(), 10, 4320, "Срок прохождения");
        saveInt("minimum-answer-score", request.minimumAnswerScore(), 60, 100, "Проходной балл");
        saveInt("max-answer-characters", request.maxAnswerCharacters(), 120, 2000, "Длина ответа");
        saveInt("max-plan-characters", request.maxPlanCharacters(), 120, 3000, "Длина плана");
        saveInt("fast-paste-seconds", request.fastPasteSeconds(), 1, 120, "Порог быстрой вставки");
        saveInt("fast-paste-min-characters", request.fastPasteMinCharacters(), 40, 2000, "Длина быстрой вставки");
        saveInt("copy-gram-size", request.copyGramSize(), 2, 12, "Размер фрагмента сравнения");
        saveInt("copy-similarity-percent", request.copySimilarityPercent(), 30, 100, "Сходство ответов");
        saveInt("ai-timeout-seconds", request.aiTimeoutSeconds(), 5, 60, "Таймаут AI");
        saveInt(
                "question-generation-max-tokens",
                request.questionGenerationMaxTokens(),
                2000,
                16000,
                "Лимит генерации вопросов"
        );
        saveInt(
                "question-generation-retry-max-tokens",
                request.questionGenerationRetryMaxTokens(),
                request.questionGenerationMaxTokens(),
                24000,
                "Лимит повторной генерации"
        );
        if (!request.enabled()) {
            List<ManagerReportReviewSession> reviews = sessionRepository.findByCompletedAtIsNull();
            closeReviews(reviews, "Аудит отключён глобально в справочнике");
            reviews.stream()
                    .map(ManagerReportReviewSession::getManagerUserId)
                    .distinct()
                    .forEach(accessPolicy::invalidate);
        }
        return response();
    }

    @Transactional
    public ManagerReportReviewManagerSettingResponse updateManager(Long managerId, boolean enabled) {
        Manager manager = managerRepository.findByIdWithUser(managerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден"));
        manager.setReportReviewEnabled(enabled);
        managerRepository.save(manager);
        if (!enabled) {
            closeReviews(
                    sessionRepository.findByManager_IdAndCompletedAtIsNull(managerId),
                    "Аудит отключён для менеджера в справочнике"
            );
        }
        if (manager.getUser() != null && manager.getUser().getId() != null) {
            accessPolicy.invalidate(manager.getUser().getId());
        }
        return managerResponse(manager);
    }

    private ManagerReportReviewSettingsResponse response() {
        List<ManagerReportReviewManagerSettingResponse> managers = managerRepository.findAllForReportReviewSettings()
                .stream()
                .sorted(Comparator.comparing(this::managerName, String.CASE_INSENSITIVE_ORDER))
                .map(this::managerResponse)
                .toList();
        return new ManagerReportReviewSettingsResponse(
                settings.getBoolean(PREFIX + "enabled", true),
                settings.getBoolean(MANAGER_GROUPS_ENABLED, true),
                settings.getBoolean(PREFIX + "restriction-enabled", true),
                settings.getInt(PREFIX + "max-question-count", 8),
                settings.getInt(PREFIX + "minimum-read-seconds", 60),
                settings.getInt(PREFIX + "test-minimum-read-seconds", 10),
                settings.getInt(PREFIX + "reminder-one-minutes", 60),
                settings.getInt(PREFIX + "reminder-three-minutes", 180),
                settings.getInt(PREFIX + "minimum-answer-score", 75),
                settings.getInt(PREFIX + "max-answer-characters", 420),
                settings.getInt(PREFIX + "max-plan-characters", 600),
                settings.getInt(PREFIX + "fast-paste-seconds", 12),
                settings.getInt(PREFIX + "fast-paste-min-characters", 140),
                settings.getInt(PREFIX + "copy-gram-size", 4),
                settings.getInt(PREFIX + "copy-similarity-percent", 65),
                settings.getInt(PREFIX + "ai-timeout-seconds", 25),
                settings.getInt(PREFIX + "question-generation-max-tokens", 8000),
                settings.getInt(PREFIX + "question-generation-retry-max-tokens", 12000),
                managers
        );
    }

    private void closeReviews(List<ManagerReportReviewSession> reviews, String reason) {
        LocalDateTime now = LocalDateTime.now();
        for (ManagerReportReviewSession review : reviews) {
            var withdrawnIssues = issueRepository.findByReview_IdOrderByQuestionIndexAsc(review.getId()).stream()
                    .filter(issue -> issue.getStatus() == ManagerReportReviewIssueStatus.PENDING)
                    .peek(issue -> issue.setStatus(ManagerReportReviewIssueStatus.WITHDRAWN))
                    .toList();
            issueRepository.saveAll(withdrawnIssues);
            review.setStatus(ManagerReportReviewStatus.COMPLETED);
            review.setCompletedAt(now);
            review.setRestrictionReleasedAt(now);
            review.setAuditRequired(false);
            review.setAnswerQuality("AUDIT_DISABLED");
            review.setAnswerQualityReason(reason);
            review.setReplyPromptMessageId(null);
            review.setCurrentQuestionIndex(review.getIssueCount());
            sessionRepository.save(review);

            ManagerReportReviewEvent event = new ManagerReportReviewEvent();
            event.setReview(review);
            event.setEventType("AUDIT_DISABLED");
            event.setActorRole("SYSTEM");
            event.setSource("admin-settings");
            event.setPayloadText(reason);
            eventRepository.save(event);
        }
    }

    private ManagerReportReviewManagerSettingResponse managerResponse(Manager manager) {
        User user = manager.getUser();
        return new ManagerReportReviewManagerSettingResponse(
                manager.getId(),
                managerName(manager),
                user != null && user.isActive(),
                manager.isReportReviewEnabled(),
                manager.getAuditTelegramGroupChatId() != null
        );
    }

    private String managerName(Manager manager) {
        User user = manager == null ? null : manager.getUser();
        if (user != null && user.getFio() != null && !user.getFio().isBlank()) {
            return user.getFio().trim();
        }
        if (user != null && user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername().trim();
        }
        return manager == null || manager.getId() == null ? "Менеджер" : "Менеджер #" + manager.getId();
    }

    private void saveInt(String suffix, int value, int minimum, int maximum, String label) {
        settings.setInt(PREFIX + suffix, value);
    }

    private void validate(ManagerReportReviewSettingsRequest request) {
        validateRange(request.maxQuestionCount(), 1, 12, "Максимум вопросов");
        validateRange(request.minimumReadSeconds(), 30, 300, "Время чтения");
        validateRange(request.testMinimumReadSeconds(), 3, 30, "Время чтения в тесте");
        validateRange(request.reminderOneMinutes(), 5, 1440, "Первое напоминание");
        validateRange(request.reminderThreeMinutes(), 10, 4320, "Срок прохождения");
        if (request.reminderThreeMinutes() <= request.reminderOneMinutes()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Срок прохождения должен быть больше времени первого напоминания"
            );
        }
        validateRange(request.minimumAnswerScore(), 60, 100, "Проходной балл");
        validateRange(request.maxAnswerCharacters(), 120, 2000, "Длина ответа");
        validateRange(request.maxPlanCharacters(), 120, 3000, "Длина плана");
        validateRange(request.fastPasteSeconds(), 1, 120, "Порог быстрой вставки");
        validateRange(request.fastPasteMinCharacters(), 40, 2000, "Длина быстрой вставки");
        validateRange(request.copyGramSize(), 2, 12, "Размер фрагмента сравнения");
        validateRange(request.copySimilarityPercent(), 30, 100, "Сходство ответов");
        validateRange(request.aiTimeoutSeconds(), 5, 60, "Таймаут AI");
        validateRange(request.questionGenerationMaxTokens(), 2000, 16000, "Лимит генерации вопросов");
        validateRange(
                request.questionGenerationRetryMaxTokens(),
                request.questionGenerationMaxTokens(),
                24000,
                "Лимит повторной генерации"
        );
    }

    private void validateRange(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    label + ": допустимо от " + minimum + " до " + maximum
            );
        }
    }
}
