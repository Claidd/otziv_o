package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercises the Telegram flow with real answer serialization and pause state transitions. */
class ManagerReportReviewEvidenceFlowTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ManagerReportReviewSessionRepository sessions;
    private ManagerReportReviewEventRepository events;
    private ManagerReportReviewQualityService quality;
    private ManagerDailyControlConcreteItemRepository sources;
    private TelegramService telegram;
    private AppSettingService settings;
    private ManagerReportReviewAccessPolicy access;
    private ManagerReportReviewIssueService issues;
    private ManagerReportReviewTaskContextService context;
    private ManagerReportReviewCommitmentService commitments;
    private ManagerReportReviewRolloutPolicy rollout;
    private UserService users;
    private ManagerReportReviewTelegramService service;
    private ManagerReportReviewSession review;
    private ManagerReportReviewQualityService.ReviewQuestion question;
    private User user;

    @BeforeEach
    void setUp() {
        sessions = mock(ManagerReportReviewSessionRepository.class);
        events = mock(ManagerReportReviewEventRepository.class);
        sources = mock(ManagerDailyControlConcreteItemRepository.class);
        telegram = mock(TelegramService.class);
        settings = mock(AppSettingService.class);
        access = mock(ManagerReportReviewAccessPolicy.class);
        issues = mock(ManagerReportReviewIssueService.class);
        context = mock(ManagerReportReviewTaskContextService.class);
        commitments = mock(ManagerReportReviewCommitmentService.class);
        rollout = mock(ManagerReportReviewRolloutPolicy.class);
        users = mock(UserService.class);
        quality = spy(new ManagerReportReviewQualityService(mock(AiProviderRouter.class), mapper, settings));
        var availability = new ManagerReportReviewAiAvailabilityService(sessions, events, access);
        service = new ManagerReportReviewTelegramService(sessions, events, quality,
                mock(ManagerSummaryFormatter.class), mock(ManagerWorkerDailyProgressService.class), sources,
                telegram, mock(NotificationMediaDeliveryService.class), users,
                mock(ManagerRepository.class), settings, mock(ManagerReportReviewOwnerNotificationService.class),
                access, mock(ManagerReportReviewAdminService.class), availability, issues, context, commitments, rollout);
        user = User.builder().id(17L).active(true).telegramChatId(700L).username("manager").build();
        review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManager(Manager.builder().id(9L).user(user).build());
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setSummaryDate(LocalDate.now());
        review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        review.setIssueCount(1);
        review.setCurrentQuestionIndex(0);
        review.setReplyPromptMessageId(777);
        review.setQuestionSentAt(LocalDateTime.now());
        review.setReportSnapshot("Короткий отчёт без подробных данных");
        review.setQuestionsContext("История вопроса и комментарий менеджера.\nСЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\nстарое состояние");
        question = new ManagerReportReviewQualityService.ReviewQuestion("Что произошло со счётом и что вы сделаете?",
                List.of("объяснение и действие"), List.of(42L));
        review.setQuestionsJson(quality.questionsJson(List.of(question)));
        when(sessions.findFirstByManagerUserIdAndRecipientChatIdAndReplyPromptMessageId(17L, 700L, 777))
                .thenReturn(Optional.of(review));
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(users.findByChatId(700L)).thenReturn(Optional.of(user));
        when(issues.isPending(review, 0)).thenReturn(true);
        var issue = new ManagerReportReviewIssue();
        issue.setId(101L);
        issue.setReview(review);
        issue.setQuestionIndex(0);
        issue.setStatus(ManagerReportReviewIssueStatus.PENDING);
        when(issues.nextPending(review, 0)).thenReturn(Optional.of(issue));
        when(issues.answeredCount(review)).thenReturn(1L);
        when(context.refresh("sourceTaskId=42", 9L)).thenReturn("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\n"
                + "{\"id\":\"invoice:189:status\",\"source\":\"invoice\",\"field\":\"status\","
                + "\"value\":\"COLLECTING\",\"sourceTaskId\":42}");
        when(telegram.sendMessageWithInlineKeyboardMessageId(eq(700L), anyString(), eq("HTML"), any()))
                .thenReturn(Optional.of(511));
    }

    @Test
    void passesFreshRelevantFactsAndPersistsFullAssessmentAndOriginalSnapshot() throws Exception {
        var assessment = assessment(ManagerReportReviewQualityService.Decision.ACCEPTED, "Проверено по источнику");
        assessWith(assessment);

        assertThat(service.handleTextMessage(700L, user, "Сверю платёж и зафиксирую результат.", 777)).isTrue();

        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(quality).assessAnswer(evidence.capture(), eq(question), anyString(), eq(false), eq(List.of()), eq(false));
        verify(context).refresh("sourceTaskId=42", 9L);
        assertThat(evidence.getValue()).contains("История вопроса", "Факты прежнего снимка:", "COLLECTING")
                .doesNotContain("Короткий отчёт без подробных данных");
        assertThat(evidence.getValue().split("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:", -1)).hasSize(2);
        var answer = mapper.readTree(review.getAnswersJson()).get(0);
        assertThat(answer.path("assessment").path("decision").asText()).isEqualTo("ACCEPTED");
        assertThat(answer.path("assessment").path("criteria")).hasSize(1);
        assertThat(answer.path("recordedAt").asText()).isNotBlank();
        verify(issues).markAnswered(review, 0);
        verify(commitments).record(review, 0, question, assessment.commitments());
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.COMPLETED);
        ArgumentCaptor<ManagerReportReviewEvent> captured = ArgumentCaptor.forClass(ManagerReportReviewEvent.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).save(captured.capture());
        assertThat(captured.getAllValues()).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("ANSWER_EVIDENCE_SNAPSHOT");
            assertThat(event.getPayloadText()).contains("COLLECTING");
        });
    }

    @Test
    void acceptedLongFastReplyDoesNotBecomeStyleOrAuthenticityViolation() {
        var acceptedLegacySignal = new ManagerReportReviewQualityService.Assessment(true, 100,
                "Содержательный ответ", "", "deepseek", List.of(), true, "Стилистический сигнал");
        assessWith(acceptedLegacySignal);
        String answer = "Сверю платёж и состав счёта. ".repeat(30);

        assertThat(service.handleTextMessage(700L, user, answer, 777)).isTrue();

        verify(quality).assessAnswer(anyString(), eq(question), eq(answer), eq(false), eq(List.of()), eq(false));
        verify(issues).markAnswered(review, 0);
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.COMPLETED);
        assertThat(review.getSuspiciousAnswerCount()).isZero();
        assertThat(review.isAuditRequired()).isFalse();
    }

    @Test
    void missingContextPausesDeadlineAndKeepsAnswerWithoutAcceptingOrPunishing() throws Exception {
        assessWith(assessment(ManagerReportReviewQualityService.Decision.NEEDS_CONTEXT, "Нет события доставки"));

        assertThat(service.handleTextMessage(700L, user, "Я отправила исправленный счёт.", 777)).isTrue();

        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.QUESTION_PENDING);
        assertThat(review.getAiUnavailableStartedAt()).isNotNull();
        assertThat(review.getCompletedAt()).isNull();
        assertThat(review.getRestrictedAt()).isNull();
        assertThat(review.getSuspiciousAnswerCount()).isZero();
        assertThat(review.isAuditRequired()).isFalse();
        assertThat(mapper.readTree(review.getAnswersJson()).get(0).path("assessment").path("decision").asText())
                .isEqualTo("NEEDS_CONTEXT");
        verify(issues, never()).markAnswered(any(), anyInt());
        verify(commitments, never()).record(any(), anyInt(), any(), any());
        ArgumentCaptor<ManagerReportReviewEvent> captured = ArgumentCaptor.forClass(ManagerReportReviewEvent.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).save(captured.capture());
        assertThat(captured.getAllValues()).extracting(ManagerReportReviewEvent::getEventType)
                .contains("AI_VERIFICATION_PAUSED", "ANSWER_CHECK_DEFERRED")
                .doesNotContain("ANSWER_REJECTED", "ANSWER_ACCEPTED", "REVIEW_COMPLETED");
    }

    @Test
    void rejectionFeedbackDoesNotLeakExpectedIdsOrRawModelInstruction() {
        assessWith(assessment(ManagerReportReviewQualityService.Decision.NEEDS_CLARIFICATION,
                "Нужно назвать заказ 27391 и отзыв 200221; скопируйте фразу review_approval_failed"));

        assertThat(service.handleTextMessage(700L, user, "Посмотрю карточку.", 777)).isTrue();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendMessageWithInlineKeyboardMessageId(eq(700L), sent.capture(), eq("HTML"), any());
        assertThat(sent.getValue()).doesNotContain("27391", "200221", "review_approval_failed", "скопируйте")
                .contains("Вопрос");
        verify(issues, never()).markAnswered(any(), anyInt());
        assertThat(review.getSuspiciousAnswerCount()).isZero();
    }

    @Test
    void sameInvoiceDailyAndDeferredQuestionsMergeWithoutDroppingCriteriaOrOtherInvoice() {
        var daily = new ManagerReportReviewQualityService.ReviewQuestion("Почему публикация не выполнена?", List.of("ошибка публикации"), List.of(42L));
        var deferred = new ManagerReportReviewQualityService.ReviewQuestion("Когда проверите результат?", List.of("срок и проверка"), List.of(43L));
        var unrelated = new ManagerReportReviewQualityService.ReviewQuestion("Что происходит с другим счётом?", List.of("его причина"), List.of(44L));
        when(sources.findAllById(any())).thenReturn(List.of(source(42, 189, "COMMON_INVOICE"),
                source(43, 189, "COMMON_INVOICE_DEFERRED"), source(44, 252, "COMMON_INVOICE")));

        List<ManagerReportReviewQualityService.ReviewQuestion> merged = ReflectionTestUtils.invokeMethod(
                service, "withGoalQuestions", List.of(daily, unrelated), List.of(deferred));

        assertThat(merged).hasSize(2);
        assertThat(merged.getFirst().sourceTaskIds()).containsExactly(42L, 43L);
        assertThat(merged.getFirst().expectedPoints()).containsExactly("ошибка публикации", "срок и проверка");
        assertThat(merged.getFirst().question()).contains(daily.question(), deferred.question());
        assertThat(merged.getLast()).isEqualTo(unrelated);
    }

    @Test
    void shadowStoresModelDecisionButCannotPassOrRestrictEvenOverdueAudit() {
        when(rollout.isShadowFor(review)).thenReturn(true);
        assessWith(assessment(ManagerReportReviewQualityService.Decision.ACCEPTED, "Все критерии выполнены"));
        review.setDeadlineStartedAt(LocalDateTime.now().minusHours(5));

        assertThat(service.handleTextMessage(700L, user, "Сверю платёж.", 777)).isTrue();

        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.QUESTION_PENDING);
        assertThat(review.getAiUnavailableStartedAt()).isNotNull();
        assertThat(review.getCompletedAt()).isNull();
        assertThat(review.getRestrictedAt()).isNull();
        verify(issues, never()).markAnswered(any(), anyInt());
        verify(commitments, never()).record(any(), anyInt(), any(), any());
        when(settings.getBoolean("manager.report-review.enabled", true)).thenReturn(true);
        when(settings.getBoolean("manager.report-review.restriction-enabled", true)).thenReturn(true);
        when(settings.getInt("manager.report-review.reminder-three-minutes", 180)).thenReturn(180);
        doReturn(true).when(quality).aiAvailable();
        when(sessions.findFirstByManagerUserIdAndTestModeFalseAndDeadlineStartedAtLessThanEqualAndAiUnavailableStartedAtIsNullAndStatusNotInAndCompletedAtIsNullOrderByDeadlineStartedAtAsc(
                eq(17L), any(), any())).thenReturn(Optional.of(review));
        when(sessions.findFirstByManagerUserIdAndTestModeFalseAndCompletedAtIsNullOrderByCreatedAtDesc(17L))
                .thenReturn(Optional.of(review));
        var actualPolicy = new ManagerReportReviewAccessPolicy(sessions, settings, quality, rollout);
        assertThat(actualPolicy.state(user).restricted()).isFalse();
        assertThat(actualPolicy.state(user).pending()).isTrue();
    }

    @Test
    void legacyPlanShadowSavesRawAnswerAndContinuesWithOriginalSubmissionTime() throws Exception {
        review.setStatus(ManagerReportReviewStatus.PLAN_PENDING);
        when(rollout.isShadowFor(review)).thenReturn(true);
        String rawPlan = "Через час проверю историю чата; результат сверю по отметке доставки.";
        doReturn(assessment(ManagerReportReviewQualityService.Decision.ACCEPTED, "План соответствует фактам"))
                .when(quality).assessAnswer(anyString(), any(), eq(rawPlan), eq(true));

        assertThat(service.handleTextMessage(700L, user, rawPlan, 777)).isTrue();

        var saved = mapper.readTree(review.getAnswersJson()).get(0);
        assertThat(saved.path("answer").asText()).isEqualTo(rawPlan);
        assertThat(saved.path("accepted").asBoolean()).isFalse();
        assertThat(saved.path("assessment").path("decision").asText()).isEqualTo("NEEDS_CONTEXT");
        assertThat(review.getAiUnavailableStartedAt()).isNotNull();
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.PLAN_PENDING);
        var originalTime = quality.latestAnswerRecordedAt(review.getAnswersJson(), -1);
        assertThat(originalTime).isNotNull();
        when(rollout.isShadowFor(review)).thenReturn(false);
        doReturn(assessment(ManagerReportReviewQualityService.Decision.NEEDS_CONTEXT, "История доставки недоступна"))
                .when(quality).assessAnswer(anyString(), any(), eq(rawPlan), eq(true), eq(List.of()), eq(false), eq(originalTime));

        String continued = ReflectionTestUtils.invokeMethod(service, "continueFlow", review, user);

        assertThat(continued).contains("повторно проверен");
        verify(quality).assessAnswer(anyString(), any(), eq(rawPlan), eq(true), eq(List.of()), eq(false), eq(originalTime));
        assertThat(quality.latestAnswerRecordedAt(review.getAnswersJson(), -1)).isEqualTo(originalTime);
        assertThat(mapper.readTree(review.getAnswersJson())).hasSize(2);
        assertThat(review.getCompletedAt()).isNull();
    }

    @Test
    void retryUsesOriginalEvidenceSnapshotEvenWhenInvoiceHasSinceBeenPaid() {
        List<ManagerReportReviewEvent> savedEvents = new java.util.ArrayList<>();
        when(events.save(any())).thenAnswer(invocation -> {
            ManagerReportReviewEvent event = invocation.getArgument(0);
            savedEvents.add(event);
            return event;
        });
        when(events.findByReview_IdOrderByCreatedAtAsc(41L)).thenAnswer(invocation -> savedEvents);
        String answer = "Счёт находится в сборе.";
        assessWith(assessment(ManagerReportReviewQualityService.Decision.NEEDS_CONTEXT, "Проверка ожидает"));
        assertThat(service.handleTextMessage(700L, user, answer, 777)).isTrue();
        var submittedAt = quality.latestAnswerRecordedAt(review.getAnswersJson(), 0);
        assertThat(submittedAt.getZone().getId()).isEqualTo("Asia/Irkutsk");
        when(context.refresh("sourceTaskId=42", 9L)).thenReturn("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\n"
                + "{\"id\":\"invoice:189:status\",\"source\":\"invoice\",\"field\":\"status\",\"value\":\"PAID\",\"sourceTaskId\":42}");
        doReturn(assessment(ManagerReportReviewQualityService.Decision.ACCEPTED, "Верно на момент ответа"))
                .when(quality).assessAnswer(anyString(), eq(question), eq(answer), eq(false), any(), eq(false), eq(submittedAt));

        String continued = ReflectionTestUtils.invokeMethod(service, "continueFlow", review, user);

        assertThat(continued).contains("по данным на момент его отправки");
        ArgumentCaptor<String> originalEvidence = ArgumentCaptor.forClass(String.class);
        verify(quality).assessAnswer(originalEvidence.capture(), eq(question), eq(answer), eq(false), any(), eq(false), eq(submittedAt));
        assertThat(originalEvidence.getValue()).contains("COLLECTING").doesNotContain("PAID");
        verify(context).refresh("sourceTaskId=42", 9L);
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.COMPLETED);
        assertThat(quality.latestAnswerRecordedAt(review.getAnswersJson(), 0)).isEqualTo(submittedAt);
    }

    @Test
    void pausedQuestionOffersNewReplyAndAssessesItAgainstFreshFacts() {
        assessWith(assessment(ManagerReportReviewQualityService.Decision.NEEDS_CONTEXT, "Не хватает данных"));
        assertThat(service.handleTextMessage(700L, user, "Прежний ответ.", 777)).isTrue();
        var pausedAt = review.getAiUnavailableStartedAt();
        verify(telegram).sendMessageWithInlineKeyboard(eq(700L), anyString(), eq("HTML"),
                org.mockito.ArgumentMatchers.argThat(keyboard -> keyboard.stream().flatMap(List::stream)
                        .anyMatch(button -> button.getText().contains("Ответить заново")
                                && button.getCallbackData().equals("manager-review:answer:41"))));
        when(telegram.sendForceReplyMessageId(eq(700L), anyString())).thenReturn(Optional.of(601));

        assertThat(service.handle(callback("answer", 700L, 700L))).contains("Напишите ответ на вопрос");

        assertThat(review.getReplyPromptMessageId()).isEqualTo(601);
        assertThat(review.getAiUnavailableStartedAt()).isEqualTo(pausedAt);
        assertThat(review.getCompletedAt()).isNull();
        when(sessions.findFirstByManagerUserIdAndRecipientChatIdAndReplyPromptMessageId(17L, 700L, 601))
                .thenReturn(Optional.of(review));
        when(context.refresh("sourceTaskId=42", 9L)).thenReturn("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\nPAID");
        assessWith(assessment(ManagerReportReviewQualityService.Decision.ACCEPTED, "Новая попытка верна"));

        assertThat(service.handleTextMessage(700L, user, "Теперь счёт оплачен.", 601)).isTrue();

        verify(quality).assessAnswer(org.mockito.ArgumentMatchers.contains("PAID"), eq(question),
                eq("Теперь счёт оплачен."), eq(false), eq(List.of("Прежний ответ.")), eq(false));
    }

    @Test
    void pausedLegacyPlanCanRestoreLostGroupPromptWithoutChangingReviewState() {
        review.setStatus(ManagerReportReviewStatus.PLAN_PENDING);
        review.setRecipientChatId(-100900L);
        review.setReplyPromptMessageId(null);
        review.setAiUnavailableStartedAt(LocalDateTime.now().minusHours(1));
        review.setRestrictedAt(LocalDateTime.now().minusHours(2));
        var pausedAt = review.getAiUnavailableStartedAt();
        var restrictedAt = review.getRestrictedAt();
        String history = legacyAnswer(-1);
        review.setAnswersJson(history);
        when(telegram.sendMessageWithInlineKeyboardMessageId(eq(-100900L), anyString(), eq(null), eq(List.of())))
                .thenReturn(Optional.of(615));
        ReflectionTestUtils.invokeMethod(service, "sendAiUnavailable", review);
        verify(telegram).sendMessageWithInlineKeyboard(eq(-100900L), anyString(), eq("HTML"),
                org.mockito.ArgumentMatchers.argThat(keyboard -> keyboard.stream().flatMap(List::stream)
                        .anyMatch(button -> button.getText().contains("Ответить заново"))));

        assertThat(service.handle(callback("answer", 700L, -100900L))).contains("Напишите новый план действий");

        assertThat(review.getReplyPromptMessageId()).isEqualTo(615);
        assertThat(review.getAnswersJson()).isEqualTo(history);
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.PLAN_PENDING);
        assertThat(review.getAiUnavailableStartedAt()).isEqualTo(pausedAt);
        assertThat(review.getRestrictedAt()).isEqualTo(restrictedAt);
        assertThat(review.getCompletedAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(context, access, commitments);
        when(sessions.findFirstByManagerUserIdAndRecipientChatIdAndReplyPromptMessageId(17L, -100900L, 615))
                .thenReturn(Optional.of(review));
        doReturn(assessment(ManagerReportReviewQualityService.Decision.NEEDS_CONTEXT, "Ожидает подтверждения"))
                .when(quality).assessAnswer(anyString(), any(), eq("Новый план."), eq(true));

        assertThat(service.handleTextMessage(-100900L, user, "Новый план.", 615)).isTrue();

        verify(quality).assessAnswer(anyString(), any(), eq("Новый план."), eq(true));
        verify(context).refresh(review.getQuestionsContext(), 9L);
    }

    @Test
    void continueLegacyQuestionWithoutTimestampRequestsNewAttemptAndPreservesHistory() {
        assertLegacyContinueRequestsNewAttempt(ManagerReportReviewStatus.QUESTION_PENDING, 0);
    }

    @Test
    void continueLegacyPlanWithoutTimestampRequestsNewAttemptAndPreservesHistory() {
        assertLegacyContinueRequestsNewAttempt(ManagerReportReviewStatus.PLAN_PENDING, -1);
    }

    @Test
    void newPlanReplyCallbackStillRejectsOtherManagersAndWrongChat() {
        review.setStatus(ManagerReportReviewStatus.PLAN_PENDING);
        review.setRecipientChatId(-100900L);
        review.setReplyPromptMessageId(null);
        User other = User.builder().id(22L).active(true).telegramChatId(800L).build();
        when(users.findByChatId(800L)).thenReturn(Optional.of(other));

        assertThat(service.handle(callback("answer", 800L, -100900L))).contains("Этот разбор назначен другому менеджеру");
        assertThat(service.handle(callback("answer", 700L, -100901L))).contains("Этот разбор назначен другому менеджеру");

        assertThat(review.getReplyPromptMessageId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(telegram, context, access, commitments);
    }

    private void assertLegacyContinueRequestsNewAttempt(ManagerReportReviewStatus status, int questionIndex) {
        review.setStatus(status);
        review.setAiUnavailableStartedAt(LocalDateTime.now().minusHours(1));
        var pausedAt = review.getAiUnavailableStartedAt();
        String history = legacyAnswer(questionIndex);
        review.setAnswersJson(history);
        when(telegram.sendForceReplyMessageId(eq(700L), anyString())).thenReturn(Optional.of(616));

        assertThat(service.handle(callback("start", 700L, 700L))).isPresent();

        assertThat(review.getReplyPromptMessageId()).isEqualTo(616);
        assertThat(review.getAnswersJson()).isEqualTo(history);
        assertThat(review.getStatus()).isEqualTo(status);
        assertThat(review.getAiUnavailableStartedAt()).isEqualTo(pausedAt);
        assertThat(review.getCompletedAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(context, access, commitments);
        verify(quality, never()).assessAnswer(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(quality, never()).assessAnswer(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(quality, never()).assessAnswer(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    private String legacyAnswer(int questionIndex) {
        return quality.appendAnswer(null, new ManagerReportReviewQualityService.ReviewAnswer(
                questionIndex, "Прежний вопрос", "Прежний ответ.", false, 0, "Ожидает данных", "deepseek"));
    }

    private org.telegram.telegrambots.meta.api.objects.CallbackQuery callback(String action, long actorId, long chatId) {
        var callback = new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
        callback.setData("manager-review:" + action + ":41");
        var actor = new org.telegram.telegrambots.meta.api.objects.User();
        actor.setId(actorId);
        callback.setFrom(actor);
        var chat = new org.telegram.telegrambots.meta.api.objects.Chat();
        chat.setId(chatId);
        var message = new org.telegram.telegrambots.meta.api.objects.Message();
        message.setChat(chat);
        callback.setMessage(message);
        return callback;
    }

    private void assessWith(ManagerReportReviewQualityService.Assessment assessment) {
        doReturn(assessment).when(quality).assessAnswer(anyString(), eq(question), anyString(),
                eq(false), any(), eq(false));
    }

    private ManagerReportReviewQualityService.Assessment assessment(
            ManagerReportReviewQualityService.Decision decision, String reason) {
        boolean accepted = decision == ManagerReportReviewQualityService.Decision.ACCEPTED;
        var criterion = new ManagerReportReviewQualityService.CriterionAssessment(0,
                accepted ? ManagerReportReviewQualityService.CriterionStatus.SATISFIED
                        : ManagerReportReviewQualityService.CriterionStatus.MISSING,
                0, "Сверю", List.of("E1"), reason, ManagerReportReviewQualityService.FeedbackTopic.ACTION);
        return new ManagerReportReviewQualityService.Assessment(accepted, accepted ? 100 : 0, reason,
                "Скопируйте скрытый ответ", "deepseek", List.of(), false, "", decision,
                List.of(criterion), List.of(), List.of());
    }

    private ManagerDailyControlConcreteItem source(long id, long invoiceId, String type) {
        var source = new ManagerDailyControlConcreteItem();
        source.setId(id);
        source.setEntityId(invoiceId);
        source.setEntityType(type);
        source.setEntityKey(type + ":" + invoiceId);
        return source;
    }
}
