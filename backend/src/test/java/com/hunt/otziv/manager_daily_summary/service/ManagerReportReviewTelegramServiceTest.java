package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDispute;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDisputeStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;

@ExtendWith(MockitoExtension.class)
class ManagerReportReviewTelegramServiceTest {

    @Mock private ManagerReportReviewSessionRepository sessionRepository;
    @Mock private ManagerReportReviewEventRepository eventRepository;
    @Mock private ManagerReportReviewQualityService qualityService;
    @Mock private ManagerSummaryFormatter formatter;
    @Mock private TelegramService telegramService;
    @Mock private NotificationMediaDeliveryService notificationMediaDeliveryService;
    @Mock private UserService userService;
    @Mock private ManagerRepository managerRepository;
    @Mock private AppSettingService appSettingService;
    @Mock private ManagerReportReviewOwnerNotificationService ownerNotificationService;
    @Mock private ManagerReportReviewAccessPolicy accessPolicy;
    @Mock private ManagerReportReviewAdminService adminService;
    @Mock private ManagerReportReviewAiAvailabilityService aiAvailabilityService;
    @Mock private ManagerReportReviewIssueService issueService;
    @Mock private ManagerReportReviewTaskContextService taskContextService;

    @InjectMocks
    private ManagerReportReviewTelegramService service;

    private User user;
    private Manager manager;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(17L)
                .username("manager")
                .fio("Менеджер")
                .telegramChatId(700L)
                .active(true)
                .build();
        manager = Manager.builder().id(9L).user(user).build();
        lenient().when(appSettingService.getBoolean("manager.report-review.enabled", true)).thenReturn(true);
        lenient().when(appSettingService.getInt("manager.report-review.minimum-read-seconds", 60)).thenReturn(60);
        lenient().when(taskContextService.refresh(any(String.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(issueService.isPending(any(ManagerReportReviewSession.class), anyInt()))
                .thenReturn(true);
        lenient().when(sessionRepository.save(any(ManagerReportReviewSession.class)))
                .thenAnswer(invocation -> {
                    ManagerReportReviewSession review = invocation.getArgument(0);
                    if (review.getId() == null) review.setId(41L);
                    return review;
                });
        lenient().when(issueService.ensureIssues(
                any(ManagerReportReviewSession.class),
                any()
        )).thenAnswer(invocation -> {
            ManagerReportReviewSession review = invocation.getArgument(0);
            List<ManagerReportReviewQualityService.ReviewQuestion> questions = invocation.getArgument(1);
            java.util.ArrayList<ManagerReportReviewIssue> issues = new java.util.ArrayList<>();
            for (int index = 0; index < questions.size(); index++) {
                issues.add(issue(review, index, questions.get(index).question()));
            }
            return issues;
        });
        lenient().when(issueService.nextPending(
                any(ManagerReportReviewSession.class),
                anyInt()
        )).thenAnswer(invocation -> {
            ManagerReportReviewSession review = invocation.getArgument(0);
            int index = invocation.getArgument(1);
            return index >= 0 && index < review.getIssueCount()
                    ? Optional.of(issue(review, index, "Вопрос " + (index + 1)))
                    : Optional.empty();
        });
    }

    @Test
    void automaticallyAcceptsDayWithoutProblems() {
        ManagerDailySummaryResponse summary = summary(0);
        when(formatter.formatPersonal(summary)).thenReturn(new ManagerFormattedReport(
                "Отчёт без замечаний",
                "<h2>Отчёт без замечаний</h2>"
        ));
        when(sessionRepository.findBySummaryDateAndManager_IdAndTestModeFalse(summary.date(), manager.getId()))
                .thenReturn(Optional.empty());
        when(telegramService.sendRichMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), eq(List.of())
        )).thenReturn(Optional.of(501));

        assertThat(service.deliver(manager, summary)).isTrue();

        verify(notificationMediaDeliveryService).sendMediaOnly(
                eq(NotificationMediaEventCatalog.MANAGER_TEAM_PROGRESS_GROWING.code()),
                eq(700L),
                eq(17L),
                any(String.class),
                eq("HTML")
        );
        verify(qualityService, never()).generateQuestions(any(String.class), anyInt());
        verify(sessionRepository, atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(review ->
                review.getStatus() == ManagerReportReviewStatus.COMPLETED
                        && review.isAutoCompleted()
                        && review.getCompletedAt() != null
        ));
    }

    @Test
    void doesNotBuildOrSendAuditForDisabledManager() {
        manager.setReportReviewEnabled(false);
        ManagerDailySummaryResponse summary = mock(ManagerDailySummaryResponse.class);

        assertThat(service.deliver(manager, summary)).isFalse();

        verify(formatter, never()).formatPersonal(any());
        verify(qualityService, never()).generateQuestions(any(String.class), anyInt());
        verify(telegramService, never()).sendRichMessageWithInlineKeyboardMessageId(
                anyLong(), any(String.class), any()
        );
    }

    @Test
    void initialAuditMessageDoesNotContainReportBeforeStudyButton() {
        ManagerDailySummaryResponse summary = summary(2);
        var questions = List.of(
                new ManagerReportReviewQualityService.ReviewQuestion("Вопрос 1", List.of("Факт 1")),
                new ManagerReportReviewQualityService.ReviewQuestion("Вопрос 2", List.of("Факт 2"))
        );
        when(formatter.formatPersonal(summary)).thenReturn(new ManagerFormattedReport(
                "СЕКРЕТНЫЙ ТЕКСТ ОТЧЁТА",
                "<h2>СЕКРЕТНЫЙ ТЕКСТ ОТЧЁТА</h2>"
        ));
        when(sessionRepository.findBySummaryDateAndManager_IdAndTestModeFalse(summary.date(), manager.getId()))
                .thenReturn(Optional.empty());
        when(qualityService.generateQuestions("СЕКРЕТНЫЙ ТЕКСТ ОТЧЁТА", 2))
                .thenReturn(new ManagerReportReviewQualityService.QuestionGeneration(questions, true, ""));
        when(qualityService.questionsJson(questions)).thenReturn("[{},{}]");
        when(telegramService.sendRichMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), any()
        )).thenReturn(Optional.of(502));

        assertThat(service.deliver(manager, summary)).isTrue();

        verify(notificationMediaDeliveryService).sendMediaOnly(
                eq(NotificationMediaEventCatalog.MANAGER_TEAM_PROGRESS_SLOWED.code()),
                eq(700L),
                eq(17L),
                any(String.class),
                eq("HTML")
        );
        verify(sessionRepository, atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(review ->
                review.getStatus() == ManagerReportReviewStatus.DELIVERED
                        && review.getIssueCount() == 2
                        && review.getTelegramMessageId() == 502
        ));
        ArgumentCaptor<String> initialMessage = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendRichMessageWithInlineKeyboardMessageId(
                eq(700L),
                initialMessage.capture(),
                eq(ManagerReportReviewTelegramService.initialKeyboard(41L))
        );
        assertThat(initialMessage.getValue())
                .contains("Текст отчёта станет доступен после нажатия кнопки")
                .doesNotContain("СЕКРЕТНЫЙ ТЕКСТ ОТЧЁТА")
                .doesNotContain("<details")
                .doesNotContain("<summary");
        verify(telegramService, never()).sendProtectedRichMessageWithInlineKeyboardMessageId(
                anyLong(), any(String.class), any()
        );
        verify(telegramService, never()).sendProtectedMessageWithInlineKeyboardMessageId(
                anyLong(), any(String.class), any(String.class), any()
        );
    }

    @Test
    void initialFallbackMessageAlsoDoesNotContainReport() {
        ManagerDailySummaryResponse summary = summary(1);
        var questions = List.of(
                new ManagerReportReviewQualityService.ReviewQuestion("Вопрос", List.of("Факт"))
        );
        when(formatter.formatPersonal(summary)).thenReturn(new ManagerFormattedReport(
                "СЕКРЕТНЫЙ ТЕКСТ ОТЧЁТА",
                "<h2>СЕКРЕТНЫЙ ТЕКСТ ОТЧЁТА</h2>"
        ));
        when(sessionRepository.findBySummaryDateAndManager_IdAndTestModeFalse(summary.date(), manager.getId()))
                .thenReturn(Optional.empty());
        when(qualityService.generateQuestions("СЕКРЕТНЫЙ ТЕКСТ ОТЧЁТА", 1))
                .thenReturn(new ManagerReportReviewQualityService.QuestionGeneration(questions, true, ""));
        when(qualityService.questionsJson(questions)).thenReturn("[{}]");
        when(telegramService.sendRichMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), any()
        )).thenReturn(Optional.empty());
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), eq("HTML"), any()
        )).thenReturn(Optional.of(503));

        assertThat(service.deliver(manager, summary)).isTrue();

        ArgumentCaptor<String> fallbackMessage = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessageWithInlineKeyboardMessageId(
                eq(700L),
                fallbackMessage.capture(),
                eq("HTML"),
                eq(ManagerReportReviewTelegramService.initialKeyboard(41L))
        );
        assertThat(fallbackMessage.getValue())
                .contains("только после нажатия кнопки")
                .doesNotContain("СЕКРЕТНЫЙ ТЕКСТ ОТЧЁТА");
    }

    @Test
    void sendsReportToLinkedManagerOwnerGroup() {
        manager.setAuditTelegramGroupChatId(-100900L);
        ManagerDailySummaryResponse summary = summary(1);
        var questions = List.of(
                new ManagerReportReviewQualityService.ReviewQuestion("Что было не так?", List.of("Факт"))
        );
        when(appSettingService.getBoolean("manager.summary.manager-groups-enabled", true)).thenReturn(true);
        when(formatter.formatPersonal(summary)).thenReturn(new ManagerFormattedReport(
                "Полный отчёт",
                "<h2>Полный отчёт</h2>"
        ));
        when(sessionRepository.findBySummaryDateAndManager_IdAndTestModeFalse(summary.date(), manager.getId()))
                .thenReturn(Optional.empty());
        when(qualityService.generateQuestions("Полный отчёт", 1))
                .thenReturn(new ManagerReportReviewQualityService.QuestionGeneration(questions, true, ""));
        when(qualityService.questionsJson(questions)).thenReturn("[{}]");
        when(telegramService.sendRichMessageWithInlineKeyboardMessageId(
                eq(-100900L), any(String.class), any()
        )).thenReturn(Optional.of(503));

        assertThat(service.deliver(manager, summary)).isTrue();

        verify(sessionRepository, atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(review ->
                review.getRecipientChatId().equals(-100900L)
                        && review.getTelegramMessageId() == 503
        ));
        verify(telegramService, never()).sendRichMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), any()
        );
    }

    @Test
    void testAuditIsSentToTesterAndNeverUsesManagerGroupOrAccessRestriction() {
        User admin = User.builder()
                .id(99L)
                .username("admin")
                .fio("Администратор")
                .telegramChatId(9900L)
                .active(true)
                .build();
        manager.setAuditTelegramGroupChatId(-100900L);
        ManagerDailySummaryResponse summary = summary(1);
        var questions = List.of(
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Разберите конкретный случай",
                        List.of("компания", "ответ", "правильное действие")
                )
        );
        when(appSettingService.getInt("manager.report-review.test-minimum-read-seconds", 10))
                .thenReturn(10);
        when(formatter.formatPersonal(summary)).thenReturn(new ManagerFormattedReport(
                "Полный отчёт",
                "<h2>Полный отчёт</h2>"
        ));
        when(qualityService.generateQuestions("Полный отчёт", 1))
                .thenReturn(new ManagerReportReviewQualityService.QuestionGeneration(questions, true, ""));
        when(qualityService.questionsJson(questions)).thenReturn("[{}]");
        when(telegramService.sendRichMessageWithInlineKeyboardMessageId(
                eq(9900L), any(String.class), any()
        )).thenReturn(Optional.of(504));

        Optional<ManagerReportReviewSession> result = service.deliverTest(admin, manager, summary);

        assertThat(result).hasValueSatisfying(review -> {
            assertThat(review.isTestMode()).isTrue();
            assertThat(review.getTestOwnerUserId()).isEqualTo(99L);
            assertThat(review.getManagerUserId()).isEqualTo(99L);
            assertThat(review.getRecipientChatId()).isEqualTo(9900L);
            assertThat(review.getTestRunId()).isPositive();
            assertThat(review.getMinimumReadSeconds()).isEqualTo(10);
        });
        verify(telegramService, never()).sendRichMessageWithInlineKeyboardMessageId(
                eq(-100900L), any(String.class), any()
        );
        verify(accessPolicy, never()).invalidate(99L);
    }

    @Test
    void managerCanLinkCurrentTelegramGroupForAudit() {
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(managerRepository.findByUserId(17L)).thenReturn(Optional.of(manager));
        when(managerRepository.findByAuditTelegramGroupChatId(-100900L)).thenReturn(Optional.empty());

        Optional<String> result = service.handleGroupCommand(-100900L, 700L, "/auditgroup");

        assertThat(result).hasValueSatisfying(value -> assertThat(value).contains("Группа привязана"));
        verify(managerRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getAuditTelegramGroupChatId().equals(-100900L)
        ));
    }

    @Test
    void doesNotConfirmReadingBeforeMinimumTime() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setStatus(ManagerReportReviewStatus.READING);
        review.setStartedAt(LocalDateTime.now());
        review.setMinimumReadSeconds(60);
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));

        CallbackQuery callback = new CallbackQuery();
        callback.setData("manager-review:confirm:41");
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                new org.telegram.telegrambots.meta.api.objects.User();
        telegramUser.setId(700L);
        callback.setFrom(telegramUser);
        callback.setMessage(messageInChat(700L));

        Optional<String> result = service.handle(callback);

        assertThat(result).hasValueSatisfying(value -> assertThat(value).contains("осталось"));
        assertThat(review.getReadingConfirmedAt()).isNull();
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.READING);
    }

    @Test
    void showsOnlyNeutralDirectionsAndKeepsInternalAnswerChecklistHidden() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setStatus(ManagerReportReviewStatus.READING);
        review.setStartedAt(LocalDateTime.now().minusMinutes(2));
        review.setMinimumReadSeconds(60);
        review.setIssueCount(1);
        review.setQuestionsJson("[{}]");
        review.setQuestionsSource("AI");
        var questions = List.of(new ManagerReportReviewQualityService.ReviewQuestion(
                "Компания «Ромашка»: клиент спросил о сроке публикации. Разберите этот случай.",
                List.of("название компании", "фактический ответ менеджера", "правильный ответ или необходимое действие")
        ));
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(qualityService.readQuestions("[{}]")).thenReturn(questions);
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), eq("HTML"), any()
        )).thenReturn(Optional.of(510));

        CallbackQuery callback = new CallbackQuery();
        callback.setData("manager-review:confirm:41");
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                new org.telegram.telegrambots.meta.api.objects.User();
        telegramUser.setId(700L);
        callback.setFrom(telegramUser);
        callback.setMessage(messageInChat(700L));

        assertThat(service.handle(callback)).contains("Первый вопрос отправлен");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessageWithInlineKeyboardMessageId(
                eq(700L), text.capture(), eq("HTML"), any()
        );
        assertThat(text.getValue())
                .contains("Подумайте")
                .contains("какой принцип работы здесь важен")
                .contains("Готовой формулировки нет")
                .doesNotContain("название компании")
                .doesNotContain("фактический ответ менеджера")
                .doesNotContain("правильный ответ или необходимое действие");
    }

    @Test
    void visiblyPausesReviewWhenQuestionGenerationIsUnavailable() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setStatus(ManagerReportReviewStatus.READING);
        review.setStartedAt(LocalDateTime.now().minusMinutes(2));
        review.setMinimumReadSeconds(10);
        review.setIssueCount(1);
        review.setReportSnapshot("Отчёт");
        review.setQuestionsJson("[]");
        review.setQuestionsSource("PENDING_AI");
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(qualityService.readQuestions("[]")).thenReturn(List.of());
        when(qualityService.generateQuestions(any(String.class), eq(1)))
                .thenReturn(new ManagerReportReviewQualityService.QuestionGeneration(
                        List.of(),
                        false,
                        "DeepSeek timeout"
                ));

        Optional<String> result = service.handle(confirmCallback(41L));

        assertThat(result).hasValueSatisfying(value -> assertThat(value).contains("DeepSeek временно недоступен"));
        assertThat(review.getReadingConfirmedAt()).isNotNull();
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.QUESTION_PENDING);
        verify(telegramService).sendMessage(
                eq(700L),
                org.mockito.ArgumentMatchers.contains("Формирую вопросы"),
                eq("HTML")
        );
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(700L),
                org.mockito.ArgumentMatchers.contains("Автоматическая проверка временно недоступна"),
                eq("HTML"),
                any()
        );
    }

    @Test
    void doesNotClaimThatReviewContinuedWhenQuestionRetryStillFails() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        review.setReadingConfirmedAt(LocalDateTime.now().minusMinutes(1));
        review.setIssueCount(8);
        review.setReportSnapshot("Большой отчёт");
        review.setQuestionsJson("[]");
        review.setQuestionsSource("PENDING_AI");
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(qualityService.readQuestions("[]")).thenReturn(List.of());
        when(qualityService.generateQuestions("Большой отчёт", 8))
                .thenReturn(new ManagerReportReviewQualityService.QuestionGeneration(
                        List.of(),
                        false,
                        "Некорректный JSON после повторной попытки"
                ));

        Optional<String> result = service.handle(startCallback(41L));

        assertThat(result).hasValueSatisfying(value -> {
            assertThat(value).contains("Вопросы пока не сформированы. Повторите немного позже");
            assertThat(value).doesNotContain("Проверка продолжена");
        });
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.QUESTION_PENDING);
        assertThat(review.getCompletedAt()).isNull();
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(700L),
                org.mockito.ArgumentMatchers.contains("Автоматическая проверка временно недоступна"),
                eq("HTML"),
                any()
        );
    }

    @Test
    void keepsReviewPendingAndShowsRetryWhenQuestionMessageCannotBeDelivered() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setStatus(ManagerReportReviewStatus.READING);
        review.setStartedAt(LocalDateTime.now().minusMinutes(2));
        review.setMinimumReadSeconds(10);
        review.setIssueCount(1);
        review.setQuestionsJson("[{}]");
        review.setQuestionsSource("AI");
        var questions = List.of(new ManagerReportReviewQualityService.ReviewQuestion(
                "Разберите замечание",
                List.of("факт")
        ));
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(qualityService.readQuestions("[{}]")).thenReturn(questions);
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), eq("HTML"), any()
        )).thenReturn(Optional.empty());

        Optional<String> result = service.handle(confirmCallback(41L));

        assertThat(result).hasValueSatisfying(value -> assertThat(value).contains("Не удалось отправить вопрос"));
        assertThat(review.getReadingConfirmedAt()).isNotNull();
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.QUESTION_PENDING);
        assertThat(review.getCompletedAt()).isNull();
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(700L),
                org.mockito.ArgumentMatchers.contains("Вопрос не удалось отправить"),
                eq("HTML"),
                any()
        );
    }

    @Test
    void sendsClarificationWithTelegramSupportedLineBreaks() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        review.setIssueCount(1);
        review.setCurrentQuestionIndex(0);
        review.setReplyPromptMessageId(777);
        review.setQuestionsJson("[{}]");
        review.setReportSnapshot("Отчёт");
        var question = new ManagerReportReviewQualityService.ReviewQuestion(
                "Разберите замечание",
                List.of("конкретное действие")
        );
        when(sessionRepository.findFirstByManagerUserIdAndRecipientChatIdAndStatusInOrderByCreatedAtDesc(
                eq(17L), eq(700L), any()
        )).thenReturn(Optional.of(review));
        when(qualityService.readQuestions("[{}]")).thenReturn(List.of(question));
        when(qualityService.previousAcceptedContext(null, 0)).thenReturn(List.of());
        when(qualityService.assessAnswer(
                eq("Отчёт"),
                eq(question),
                eq("Я ознакомилась"),
                eq(false),
                eq(List.of()),
                eq(false)
        )).thenReturn(new ManagerReportReviewQualityService.Assessment(
                false,
                30,
                "Недостаточно конкретно.\nНужно назвать действие.",
                "Что вы проверите?\nНазовите один шаг.",
                "deepseek",
                List.of("конкретное действие"),
                false,
                ""
        ));
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), eq("HTML"), any()
        )).thenReturn(Optional.of(511));

        assertThat(service.handleTextMessage(
                700L,
                user,
                "Я ознакомилась",
                777
        )).isTrue();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessageWithInlineKeyboardMessageId(
                eq(700L), text.capture(), eq("HTML"), any()
        );
        assertThat(text.getValue())
                .contains("Недостаточно конкретно.\nНужно назвать действие.")
                .contains("Что вы проверите?\nНазовите один шаг.")
                .doesNotContain("<br>");
    }

    @Test
    void numbersOnlyQuestionsThatRemainInTheAudit() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        review.setIssueCount(2);
        review.setCurrentQuestionIndex(0);
        review.setQuestionsJson("[{},{}]");
        var firstQuestion = new ManagerReportReviewQualityService.ReviewQuestion(
                "Уже выполненная задача",
                List.of()
        );
        var secondQuestion = new ManagerReportReviewQualityService.ReviewQuestion(
                "Единственная действующая задача",
                List.of()
        );
        ManagerReportReviewIssue withdrawn = issue(review, 0, firstQuestion.question());
        withdrawn.setStatus(ManagerReportReviewIssueStatus.WITHDRAWN);
        ManagerReportReviewIssue pending = issue(review, 1, secondQuestion.question());
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(qualityService.readQuestions("[{},{}]")).thenReturn(List.of(firstQuestion, secondQuestion));
        when(issueService.nextPending(eq(review), anyInt())).thenReturn(Optional.of(pending));
        when(issueService.issues(review)).thenReturn(List.of(withdrawn, pending));
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), eq("HTML"), any()
        )).thenReturn(Optional.of(512));

        assertThat(service.handle(startCallback(41L))).contains("Вопрос отправлен");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessageWithInlineKeyboardMessageId(
                eq(700L), text.capture(), eq("HTML"), any()
        );
        assertThat(text.getValue())
                .contains("Вопрос 1 из 1")
                .contains("Единственная действующая задача")
                .doesNotContain("Вопрос 2 из 2");
    }

    @Test
    void onlyAssignedManagerCanOpenReportInsideGroup() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setManagerName("Менеджер");
        review.setRecipientChatId(-100900L);
        review.setStatus(ManagerReportReviewStatus.DELIVERED);
        review.setReportSnapshot("Отчёт");
        review.setReportRichSnapshot("<h2>Отчёт</h2>");
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(telegramService.sendRichMessageWithInlineKeyboardMessageId(
                eq(-100900L), any(String.class), any()
        )).thenReturn(Optional.of(504));

        CallbackQuery callback = new CallbackQuery();
        callback.setData("manager-review:study:41");
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                new org.telegram.telegrambots.meta.api.objects.User();
        telegramUser.setId(700L);
        callback.setFrom(telegramUser);
        callback.setMessage(messageInChat(-100900L));

        assertThat(service.handle(callback)).contains("Отчёт открыт для изучения");
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.READING);
        assertThat(review.getStartedAt()).isNotNull();
        assertThat(review.getDeadlineStartedAt()).isNotNull();
        ArgumentCaptor<String> openedReport = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendRichMessageWithInlineKeyboardMessageId(
                eq(-100900L),
                openedReport.capture(),
                eq(ManagerReportReviewTelegramService.readingKeyboard(41L))
        );
        assertThat(openedReport.getValue()).contains("<h2>Отчёт</h2>");
        verify(telegramService).sendMessage(
                eq(-100900L),
                org.mockito.ArgumentMatchers.contains("начал(а) изучение"),
                eq("HTML")
        );
    }

    @Test
    void failedReportOpeningDoesNotStartReadingTimer() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setManagerName("Менеджер");
        review.setRecipientChatId(700L);
        review.setTelegramMessageId(501);
        review.setStatus(ManagerReportReviewStatus.DELIVERED);
        review.setReportSnapshot("Полный отчёт");
        review.setReportRichSnapshot("<h2>Полный отчёт</h2>");
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(telegramService.editRichMessage(eq(700L), eq(501), any(String.class), any()))
                .thenReturn(false);
        when(telegramService.sendRichMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), any()
        )).thenReturn(Optional.empty());
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(700L), any(String.class), eq("HTML"), any()
        )).thenReturn(Optional.empty());

        assertThat(service.handle(callback("study", 41L)))
                .hasValueSatisfying(value -> assertThat(value).contains("Не удалось открыть отчёт"));
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.DELIVERED);
        assertThat(review.getStartedAt()).isNull();
        assertThat(review.getDeadlineStartedAt()).isNull();
        verify(accessPolicy, never()).invalidate(17L);
    }

    @Test
    void repeatedStudyClickDoesNotResendReportOrRestartTimer() {
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(1);
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setStatus(ManagerReportReviewStatus.READING);
        review.setStartedAt(startedAt);
        review.setDeadlineStartedAt(startedAt);
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));

        assertThat(service.handle(callback("study", 41L)))
                .hasValueSatisfying(value -> assertThat(value).contains("уже открыт"));
        assertThat(review.getStartedAt()).isEqualTo(startedAt);
        assertThat(review.getDeadlineStartedAt()).isEqualTo(startedAt);
        verify(telegramService, never()).editRichMessage(anyLong(), anyInt(), any(), any());
        verify(telegramService, never()).sendRichMessageWithInlineKeyboardMessageId(
                anyLong(), any(String.class), any()
        );
    }

    @Test
    void groupAnswerPromptDoesNotForceReplyForAllParticipants() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(-100900L);
        review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        review.setIssueCount(1);
        review.setCurrentQuestionIndex(0);
        review.setQuestionsJson("[{}]");
        var question = new ManagerReportReviewQualityService.ReviewQuestion(
                "Почему клиенту не ответили вовремя?",
                List.of("причина", "действие")
        );
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(qualityService.readQuestions("[{}]")).thenReturn(List.of(question));
        when(issueService.issues(review)).thenReturn(List.of(issue(review, 0, question.question())));
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(-100900L), any(String.class), eq(null), eq(List.of())
        )).thenReturn(Optional.of(515));

        CallbackQuery callback = callback("answer", 41L);
        callback.setMessage(messageInChat(-100900L));

        assertThat(service.handle(callback)).hasValueSatisfying(value ->
                assertThat(value).contains("Напишите ответ")
        );
        assertThat(review.getReplyPromptMessageId()).isEqualTo(515);
        verify(telegramService).sendMessageWithInlineKeyboardMessageId(
                eq(-100900L),
                org.mockito.ArgumentMatchers.contains("нажмите «Ответить»"),
                eq(null),
                eq(List.of())
        );
        verify(telegramService, never()).sendForceReplyMessageId(eq(-100900L), any(String.class));
        verify(telegramService, never()).sendForceReplyMessage(eq(-100900L), any(String.class));
    }

    @Test
    void ownerCanSeeGroupReportButCannotPassReviewInsteadOfManager() {
        User owner = User.builder()
                .id(99L)
                .username("owner")
                .telegramChatId(800L)
                .active(true)
                .build();
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(-100900L);
        review.setStatus(ManagerReportReviewStatus.DELIVERED);
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(800L)).thenReturn(Optional.of(owner));

        CallbackQuery callback = new CallbackQuery();
        callback.setData("manager-review:study:41");
        org.telegram.telegrambots.meta.api.objects.User telegramOwner =
                new org.telegram.telegrambots.meta.api.objects.User();
        telegramOwner.setId(800L);
        callback.setFrom(telegramOwner);
        callback.setMessage(messageInChat(-100900L));

        assertThat(service.handle(callback)).hasValueSatisfying(value ->
                assertThat(value).contains("назначен другому менеджеру")
        );
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.DELIVERED);
        verify(telegramService, never()).editRichMessage(eq(-100900L), anyInt(), any(), any());
    }

    @Test
    void disputeButtonRequiresSelectingOneConcreteIssue() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(-100900L);
        review.setStatus(ManagerReportReviewStatus.READING);
        review.setIssueCount(2);
        review.setQuestionsJson("[{},{}]");
        ManagerReportReviewIssue first = issue(review, 0, "Неверный процент выполнения");
        ManagerReportReviewIssue second = issue(review, 1, "Формальный ответ клиенту");
        when(sessionRepository.findForUpdateById(41L)).thenReturn(Optional.of(review));
        when(userService.findByChatId(700L)).thenReturn(Optional.of(user));
        when(qualityService.readQuestions("[{},{}]")).thenReturn(List.of(
                new ManagerReportReviewQualityService.ReviewQuestion(first.getQuestionText(), List.of()),
                new ManagerReportReviewQualityService.ReviewQuestion(second.getQuestionText(), List.of())
        ));
        when(issueService.selectableIssues(review)).thenReturn(List.of(first, second));

        CallbackQuery callback = new CallbackQuery();
        callback.setData("manager-review:dispute:41");
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                new org.telegram.telegrambots.meta.api.objects.User();
        telegramUser.setId(700L);
        callback.setFrom(telegramUser);
        callback.setMessage(messageInChat(-100900L));

        assertThat(service.handle(callback)).contains("Выберите замечание");
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.READING);
        ArgumentCaptor<List<List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>>>
                keyboard = ArgumentCaptor.forClass(List.class);
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(-100900L),
                org.mockito.ArgumentMatchers.contains("только к выбранному пункту"),
                eq("HTML"),
                keyboard.capture()
        );
        assertThat(keyboard.getValue())
                .extracting(row -> row.getFirst().getCallbackData())
                .containsExactly(
                        "manager-review:dispute-issue-100:41",
                        "manager-review:dispute-issue-101:41"
                );
    }

    @Test
    void freeTextBeforeReadingConfirmationIsNotSilentlyIgnored() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(-100900L);
        review.setStatus(ManagerReportReviewStatus.READING);
        when(sessionRepository.findFirstByManagerUserIdAndRecipientChatIdAndStatusInOrderByCreatedAtDesc(
                eq(17L), eq(-100900L), any()
        )).thenReturn(Optional.of(review));

        assertThat(service.handleTextMessage(
                -100900L,
                user,
                "Я хочу сразу пояснить ситуацию",
                null
        )).isTrue();

        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(-100900L),
                org.mockito.ArgumentMatchers.contains("не засчитано как ответ"),
                eq("HTML"),
                eq(ManagerReportReviewTelegramService.readingKeyboard(41L))
        );
    }

    private Message messageInChat(long chatId) {
        Chat chat = new Chat();
        chat.setId(chatId);
        Message message = new Message();
        message.setChat(chat);
        return message;
    }

    private CallbackQuery confirmCallback(long reviewId) {
        return callback("confirm", reviewId);
    }

    private CallbackQuery startCallback(long reviewId) {
        return callback("start", reviewId);
    }

    private CallbackQuery callback(String action, long reviewId) {
        CallbackQuery callback = new CallbackQuery();
        callback.setData("manager-review:" + action + ":" + reviewId);
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                new org.telegram.telegrambots.meta.api.objects.User();
        telegramUser.setId(700L);
        callback.setFrom(telegramUser);
        callback.setMessage(messageInChat(700L));
        return callback;
    }

    private ManagerReportReviewIssue issue(
            ManagerReportReviewSession review,
            int index,
            String question
    ) {
        ManagerReportReviewIssue issue = new ManagerReportReviewIssue();
        issue.setId(100L + index);
        issue.setReview(review);
        issue.setQuestionIndex(index);
        issue.setTitle("Замечание " + (index + 1));
        issue.setQuestionText(question);
        issue.setStatus(ManagerReportReviewIssueStatus.PENDING);
        return issue;
    }

    private ManagerDailySummaryResponse summary(long problems) {
        ManagerDailySummaryResponse summary = mock(ManagerDailySummaryResponse.class);
        when(summary.date()).thenReturn(LocalDate.of(2026, 7, 25));
        when(summary.managerName()).thenReturn("Менеджер");
        when(summary.problemCount()).thenReturn(problems);
        return summary;
    }
}
