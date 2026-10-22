package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerReportReviewQueryServiceTest {
    @Mock ManagerReportReviewSessionRepository sessions;
    @Mock ManagerReportReviewEventRepository events;
    @Mock ManagerReportReviewIssueService issues;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalDate date = LocalDate.of(2026, 10, 22);
    private ManagerReportReviewQueryService service;
    private ManagerReportReviewSession review;

    @BeforeEach
    void setUp() {
        service = new ManagerReportReviewQueryService(sessions, events, mapper, issues);
        review = new ManagerReportReviewSession();
        review.setId(157L);
        review.setSummaryDate(date);
        when(sessions.findBySummaryDateOrderByManagerNameAsc(date)).thenReturn(List.of(review));
        when(events.findByReview_IdOrderByCreatedAtAsc(157L)).thenReturn(List.of());
        when(issues.issues(review)).thenReturn(List.of());
        when(issues.disputes(review)).thenReturn(List.of());
    }

    @Test
    void exposesStoredCriteriaAndClaimsWithoutReassessingTheAnswer() {
        review.setQuestionsJson("""
                [{"question":"Каков статус?","expectedPoints":["Текущий статус"]}]
                """);
        review.setAnswersJson("""
                [{"questionIndex":0,"question":"Каков статус?","answer":"Счёт собирается", "accepted":false,
                  "reason":"Ожидается сверка владельцем","recordedAt":"2026-10-22T12:30:00",
                  "assessment":{"decision":"NEEDS_CONTEXT","criteria":[
                    {"criterionIndex":0,"status":"SATISFIED","answerAttempt":0,"answerQuote":"Счёт собирается",
                     "evidenceRefs":["E1"],"reason":"Совпадает со статусом"}],
                    "claims":[{"kind":"FACT","status":"SUPPORTED","answerAttempt":0,"answerQuote":"Счёт собирается",
                     "evidenceRefs":["E1"],"reason":"Серверный факт"}],"rawProviderResponse":"must-not-export"}}]
                """);
        var response = service.reviews(date).getFirst();
        var answer = response.answerAttempts().getFirst();
        assertThat(answer.attemptNumber()).isEqualTo(1);
        assertThat(answer.answer()).isEqualTo("Счёт собирается");
        assertThat(answer.recordedAt()).isEqualTo("2026-10-22T12:30");
        assertThat(answer.decision()).isEqualTo("NEEDS_CONTEXT");
        assertThat(answer.structuredAssessment()).isTrue();
        assertThat(answer.criteria().getFirst().criterion()).isEqualTo("Текущий статус");
        assertThat(answer.criteria().getFirst().status()).isEqualTo("SATISFIED");
        assertThat(answer.claims().getFirst().evidenceRefs()).containsExactly("E1");
        assertThat(answer.toString()).doesNotContain("must-not-export");
    }

    @Test
    void preservesHistoricalAnswerAndMarksMissingStructuredAssessment() {
        review.setAnswersJson("""
                [{"questionIndex":0,"question":"Вопрос","answer":"Старый ответ","accepted":true,
                  "recordedAt":"не дата","reason":"Прежняя оценка"}]
                """);
        var answer = service.reviews(date).getFirst().answerAttempts().getFirst();
        assertThat(answer.decision()).isEqualTo("LEGACY_ACCEPTED");
        assertThat(answer.recordedAt()).isNull();
        assertThat(answer.structuredAssessment()).isFalse();
        assertThat(answer.criteria()).isEmpty();
        assertThat(answer.claims()).isEmpty();
    }

    @Test
    void preservesLongAnswerAndRedactsCredentialValuesInAnswerAndEvent() throws Exception {
        String longAnswer = "Подробный ответ. ".repeat(250) + " api_key=private-value Bearer confidential.jwt.value";
        review.setAnswersJson(mapper.writeValueAsString(List.of(java.util.Map.of("answer", longAnswer))));
        ManagerReportReviewEvent event = new ManagerReportReviewEvent();
        event.setPayloadText("Authorization: Bearer should-not-export\nИсточник доступен");
        when(events.findByReview_IdOrderByCreatedAtAsc(157L)).thenReturn(List.of(event));
        var response = service.reviews(date).getFirst();
        assertThat(response.answerAttempts().getFirst().answer()).startsWith("Подробный ответ. ".repeat(250));
        assertThat(response.answerAttempts().getFirst().answer()).contains("[скрыто]")
                .doesNotContain("private-value", "confidential.jwt.value");
        assertThat(response.events().getFirst().payload()).contains("Источник доступен").doesNotContain("should-not-export");
    }

    @Test
    void malformedHistoricalJsonDoesNotPreventReviewHistoryFromLoading() {
        review.setAnswersJson("{malformed");
        review.setQuestionsJson("null");
        assertThat(service.reviews(date).getFirst().answerAttempts()).isEmpty();
    }
}
