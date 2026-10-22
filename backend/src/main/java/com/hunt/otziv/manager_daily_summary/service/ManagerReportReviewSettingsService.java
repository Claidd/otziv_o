package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewManagerSettingResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewEvidenceSettings;
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
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
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
        // Legacy JSON fields remain readable for old clients; they no longer control evidence verification.
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
        if (request.evidence() != null) {
            saveEvidence(request.evidence());
            managerRepository.findAllForReportReviewSettings().stream()
                    .map(Manager::getUser).filter(java.util.Objects::nonNull)
                    .map(User::getId).filter(java.util.Objects::nonNull).forEach(accessPolicy::invalidate);
        }
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
                managers,
                evidenceSettings()
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
        validateRange(request.questionGenerationMaxTokens(), 2000, 16000, "Лимит генерации вопросов");
        validateRange(
                request.questionGenerationRetryMaxTokens(),
                request.questionGenerationMaxTokens(),
                24000,
                "Лимит повторной генерации"
        );
        if (request.evidence() != null) validateEvidence(request.evidence());
    }

    private ManagerReportReviewEvidenceSettings evidenceSettings() {
        return new ManagerReportReviewEvidenceSettings(
                settings.getString("manager-control.diagnostics.mode", "SHADOW"),
                readIds("manager-control.diagnostics.pilot-manager-ids"),
                settings.getInt("manager-control.diagnostics.max-requests-per-hour", 60),
                settings.getInt("manager-control.diagnostics.max-output-tokens", 8000),
                settings.getInt("manager-control.diagnostics.timeout-seconds", 45),
                settings.getBoolean("manager-control.diagnostics.thinking-enabled", true),
                settings.getString(PREFIX + "evidence-mode", "SHADOW"),
                readIds(PREFIX + "evidence-pilot-manager-ids"),
                settings.getInt(PREFIX + "answer-assessment-max-tokens", 6000),
                settings.getInt(PREFIX + "answer-assessment-timeout-seconds", 90),
                settings.getBoolean(PREFIX + "answer-assessment-thinking", true),
                settings.getInt(PREFIX + "question-generation-timeout-seconds", 90),
                settings.getBoolean(PREFIX + "question-generation-thinking", true),
                settings.getInt(PREFIX + "evidence-max-age-minutes", 15));
    }

    private void validateEvidence(ManagerReportReviewEvidenceSettings value) {
        String diagnosis = mode(value.diagnosisMode(), Set.of("OFF", "SHADOW", "PILOT", "ENFORCE"));
        String answer = mode(value.answerMode(), Set.of("SHADOW", "PILOT", "ENFORCE"));
        validatePilotIds(value.diagnosisPilotManagerIds(), diagnosis, "диагностики");
        validatePilotIds(value.answerPilotManagerIds(), answer, "проверки ответов");
        validateRange(value.diagnosisMaxRequestsPerHour(), 1, 1000, "Запросов диагностики в час");
        validateRange(value.diagnosisMaxOutputTokens(), 1500, 12000, "Лимит ответа диагностики");
        validateRange(value.diagnosisTimeoutSeconds(), 10, 60, "Таймаут диагностики");
        validateRange(value.answerMaxTokens(), 2000, 16000, "Лимит проверки ответа");
        validateRange(value.answerTimeoutSeconds(), 5, 240, "Таймаут проверки ответа");
        validateRange(value.questionTimeoutSeconds(), 5, 240, "Таймаут генерации вопросов");
        validateRange(value.evidenceMaxAgeMinutes(), 1, 120, "Максимальный возраст фактов");
    }

    private void validatePilotIds(List<Long> ids, String mode, String label) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || id <= 0) || ids.size() > 100
                || joinIds(ids).length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный список менеджеров для " + label);
        }
        if ("PILOT".equals(mode) && ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите менеджеров пилота " + label);
        }
        for (Long id : ids.stream().distinct().toList()) {
            if (!managerRepository.existsById(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Менеджер #" + id + " не найден");
            }
        }
    }

    private String mode(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный режим проверки");
        return normalized;
    }

    private void saveEvidence(ManagerReportReviewEvidenceSettings value) {
        settings.setString("manager-control.diagnostics.mode", mode(value.diagnosisMode(), Set.of("OFF", "SHADOW", "PILOT", "ENFORCE")));
        settings.setString("manager-control.diagnostics.pilot-manager-ids", joinIds(value.diagnosisPilotManagerIds()));
        settings.setInt("manager-control.diagnostics.max-requests-per-hour", value.diagnosisMaxRequestsPerHour());
        settings.setInt("manager-control.diagnostics.max-output-tokens", value.diagnosisMaxOutputTokens());
        settings.setInt("manager-control.diagnostics.timeout-seconds", value.diagnosisTimeoutSeconds());
        settings.setBoolean("manager-control.diagnostics.thinking-enabled", value.diagnosisThinkingEnabled());
        settings.setString(PREFIX + "evidence-mode", mode(value.answerMode(), Set.of("SHADOW", "PILOT", "ENFORCE")));
        settings.setString(PREFIX + "evidence-pilot-manager-ids", joinIds(value.answerPilotManagerIds()));
        settings.setInt(PREFIX + "answer-assessment-max-tokens", value.answerMaxTokens());
        settings.setInt(PREFIX + "answer-assessment-timeout-seconds", value.answerTimeoutSeconds());
        settings.setBoolean(PREFIX + "answer-assessment-thinking", value.answerThinkingEnabled());
        settings.setInt(PREFIX + "question-generation-timeout-seconds", value.questionTimeoutSeconds());
        settings.setBoolean(PREFIX + "question-generation-thinking", value.questionThinkingEnabled());
        settings.setInt(PREFIX + "evidence-max-age-minutes", value.evidenceMaxAgeMinutes());
    }

    private List<Long> readIds(String key) {
        String value = settings.getString(key, "");
        return Arrays.stream((value == null ? "" : value).split(",")).map(String::trim)
                .filter(id -> id.matches("[0-9]{1,18}")).map(Long::valueOf).filter(id -> id > 0).distinct().sorted().toList();
    }

    private String joinIds(List<Long> ids) {
        return ids.stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
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
