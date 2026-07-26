package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewEventResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewIssueResponse;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDispute;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDisputeStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewQueryService {

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
                                event.getPayloadText(),
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
                events
        );
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
