package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewEventResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewIssueResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewAnswerAttemptResponse;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDispute;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDisputeStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewQueryService {

    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----", Pattern.DOTALL);
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)\\b(authorization|cookie|set-cookie)\\s*:\\s*[^\\r\\n]+");
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(api[_-]?key|password|secret|access[_-]?token|refresh[_-]?token|token)[\\\"']?\\s*[=:]\\s*[\\\"']?[^\\s,;\\\"'}&]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{10,}\\b");

    private final ManagerReportReviewSessionRepository sessionRepository;
    private final ManagerReportReviewEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final ManagerReportReviewIssueService issueService;

    @Transactional(readOnly = true)
    public List<ManagerReportReviewResponse> reviews(LocalDate date) {
        LocalDate selected = date;
        if (selected == null) {
            selected = sessionRepository.findTopByOrderBySummaryDateDesc()
                    .map(ManagerReportReviewSession::getSummaryDate)
                    .orElse(LocalDate.now());
        }
        return sessionRepository.findBySummaryDateOrderByManagerNameAsc(selected).stream()
                .map(this::response)
                .toList();
    }

    private ManagerReportReviewResponse response(ManagerReportReviewSession review) {
        List<ManagerReportReviewEventResponse> events =
                eventRepository.findByReview_IdOrderByCreatedAtAsc(review.getId()).stream()
                        .map(event -> new ManagerReportReviewEventResponse(
                                event.getId(),
                                event.getEventType(),
                                event.getActorUserId(),
                                event.getActorRole(),
                                event.getSource(),
                                safeDisplayText(event.getPayloadText()),
                                event.getCreatedAt()
                        ))
                        .toList();
        int questionCount = arraySize(review.getQuestionsJson());
        int attempts = arraySize(review.getAnswersJson());
        int accepted = acceptedAnswers(review.getAnswersJson());
        long totalReviewSeconds = totalReviewSeconds(review);
        Map<Long, ManagerReportReviewDispute> latestDisputeByIssue = issueService.disputes(review).stream()
                .filter(dispute -> dispute.getIssue() != null && dispute.getIssue().getId() != null)
                .collect(Collectors.toMap(
                        dispute -> dispute.getIssue().getId(),
                        Function.identity(),
                        (left, right) -> right
                ));
        List<ManagerReportReviewIssueResponse> issues = issueService.issues(review).stream()
                .map(issue -> {
                    ManagerReportReviewDispute dispute = latestDisputeByIssue.get(issue.getId());
                    return new ManagerReportReviewIssueResponse(
                            issue.getId(),
                            issue.getQuestionIndex(),
                            issue.getTitle(),
                            issue.getQuestionText(),
                            issue.getStatus() == null ? "" : issue.getStatus().name(),
                            dispute == null ? null : dispute.getId(),
                            dispute == null || dispute.getStatus() == null ? null : dispute.getStatus().name(),
                            dispute == null ? null : dispute.getManagerText(),
                            dispute == null ? null : dispute.getOwnerComment(),
                            dispute == null ? null : dispute.getSubmittedAt(),
                            dispute == null ? null : dispute.getResolvedAt()
                    );
                })
                .toList();
        int openDisputes = (int) issueService.disputes(review).stream()
                .filter(dispute -> dispute.getStatus() == ManagerReportReviewDisputeStatus.DRAFT
                        || dispute.getStatus() == ManagerReportReviewDisputeStatus.OPEN
                        || dispute.getStatus() == ManagerReportReviewDisputeStatus.NEEDS_CONTEXT)
                .count();
        return new ManagerReportReviewResponse(
                review.getId(),
                review.getSummaryDate(),
                review.getManager() == null ? null : review.getManager().getId(),
                review.getManagerUserId(),
                review.getManagerName(),
                review.isTestMode(),
                review.getTestOwnerUserId(),
                review.getStatus() == null ? "" : review.getStatus().name(),
                review.getCurrentQuestionIndex(),
                review.getIssueCount(),
                questionCount,
                attempts,
                accepted,
                review.getMinimumReadSeconds(),
                review.getReadSeconds(),
                totalReviewSeconds,
                review.getCompletedAt() != null && review.getReadSeconds() < review.getMinimumReadSeconds(),
                review.getQuestionsSource(),
                review.getAiUnavailableStartedAt() != null,
                Math.max(0, review.getAiUnavailableSeconds()),
                Math.max(0, review.getSuspiciousAnswerCount()),
                review.getAnswerQuality(),
                review.getAnswerQualityReason(),
                review.getActionPlan(),
                review.isAuditRequired(),
                review.isAutoCompleted(),
                review.getDisputeText(),
                review.getDeliveredAt(),
                review.getStartedAt(),
                review.getReadingConfirmedAt(),
                review.getDeadlineStartedAt(),
                review.getCompletedAt(),
                review.getDisputedAt(),
                review.getReminderOneSentAt(),
                review.getReminderThreeSentAt(),
                review.getRestrictedAt(),
                review.getRestrictionReleasedAt(),
                openDisputes,
                issues,
                events,
                answerAttempts(review)
        );
    }

    private List<ManagerReportReviewAnswerAttemptResponse> answerAttempts(ManagerReportReviewSession review) {
        JsonNode answers = jsonArray(review.getAnswersJson());
        JsonNode questions = jsonArray(review.getQuestionsJson());
        List<ManagerReportReviewAnswerAttemptResponse> result = new ArrayList<>();
        int attemptNumber = 0;
        for (JsonNode answer : answers) {
            attemptNumber++;
            if (!answer.isObject()) continue;
            int questionIndex = answer.path("questionIndex").asInt(-1);
            JsonNode points = questionIndex >= 0 && questionIndex < questions.size()
                    ? questions.path(questionIndex).path("expectedPoints") : objectMapper.createArrayNode();
            JsonNode assessment = answer.path("assessment");
            boolean structured = assessment.isObject() && !assessment.path("decision").asText("").isBlank();
            List<ManagerReportReviewAnswerAttemptResponse.Criterion> criteria = new ArrayList<>();
            if (assessment.path("criteria").isArray()) for (JsonNode criterion : assessment.path("criteria")) {
                if (!criterion.isObject()) continue;
                int index = criterion.path("criterionIndex").asInt(-1);
                String label = index >= 0 && points.isArray() && index < points.size()
                        ? safeDisplayText(points.path(index).asText("")) : "";
                criteria.add(new ManagerReportReviewAnswerAttemptResponse.Criterion(index, label,
                        text(criterion, "status"), criterion.path("answerAttempt").asInt(-1),
                        text(criterion, "answerQuote"), references(criterion.path("evidenceRefs")), text(criterion, "reason")));
            }
            List<ManagerReportReviewAnswerAttemptResponse.Claim> claims = new ArrayList<>();
            if (assessment.path("claims").isArray()) for (JsonNode claim : assessment.path("claims")) {
                if (!claim.isObject()) continue;
                claims.add(new ManagerReportReviewAnswerAttemptResponse.Claim(text(claim, "kind"), text(claim, "status"),
                        claim.path("answerAttempt").asInt(-1), text(claim, "answerQuote"),
                        references(claim.path("evidenceRefs")), text(claim, "reason")));
            }
            result.add(new ManagerReportReviewAnswerAttemptResponse(attemptNumber, questionIndex,
                    text(answer, "question"), text(answer, "answer"), validRecordedAt(answer.path("recordedAt").asText("")),
                    structured ? text(assessment, "decision") : answer.path("accepted").asBoolean(false) ? "LEGACY_ACCEPTED" : "LEGACY_REJECTED",
                    text(answer, "reason"), structured, List.copyOf(criteria), List.copyOf(claims)));
        }
        return List.copyOf(result);
    }

    private JsonNode jsonArray(String json) {
        try {
            JsonNode value = objectMapper.readTree(json == null ? "[]" : json);
            return value != null && value.isArray() ? value : objectMapper.createArrayNode();
        } catch (Exception ignored) {
            return objectMapper.createArrayNode();
        }
    }

    private String validRecordedAt(String value) {
        try { return LocalDateTime.parse(value).toString(); } catch (RuntimeException ignored) { }
        try { return java.time.ZonedDateTime.parse(value).toOffsetDateTime().toString(); } catch (RuntimeException ignored) { }
        try { return OffsetDateTime.parse(value).toString(); } catch (RuntimeException ignored) { return null; }
    }

    private List<String> references(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode reference : node) if (reference.isTextual()) result.add(safeDisplayText(reference.asText()));
        return List.copyOf(result);
    }

    private String text(JsonNode node, String field) { return safeDisplayText(node.path(field).asText("")); }

    /** Keep the complete recorded text while hiding recognizable credential values. Never expose raw provider envelopes. */
    private String safeDisplayText(String value) {
        if (value == null) return "";
        String text = PRIVATE_KEY.matcher(value).replaceAll("[ключ скрыт]");
        text = AUTH_HEADER.matcher(text).replaceAll("$1: [скрыто]");
        text = CREDENTIAL.matcher(text).replaceAll("$1=[скрыто]");
        text = BEARER.matcher(text).replaceAll("Bearer [скрыто]");
        return API_KEY.matcher(text).replaceAll("[ключ скрыт]");
    }

    private long totalReviewSeconds(ManagerReportReviewSession review) {
        if (review.getStartedAt() == null) return 0;
        LocalDateTime finished = review.getCompletedAt() != null
                ? review.getCompletedAt()
                : review.getDisputedAt() != null
                        ? review.getDisputedAt()
                        : LocalDateTime.now();
        return Math.max(0, Duration.between(review.getStartedAt(), finished).toSeconds());
    }

    private int arraySize(String json) {
        try {
            JsonNode value = objectMapper.readTree(json == null ? "[]" : json);
            return value.isArray() ? value.size() : 0;
        } catch (Exception exception) {
            return 0;
        }
    }

    private int acceptedAnswers(String json) {
        try {
            JsonNode value = objectMapper.readTree(json == null ? "[]" : json);
            if (!value.isArray()) return 0;
            int accepted = 0;
            for (JsonNode answer : value) {
                if (answer.path("accepted").asBoolean(false)) accepted++;
            }
            return accepted;
        } catch (Exception exception) {
            return 0;
        }
    }
}
