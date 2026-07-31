package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDispute;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerReportReviewTelegramService {

    private static final String CALLBACK_PREFIX = "manager-review:";
    private static final String START = "start";
    private static final String STUDY = "study";
    private static final String CONFIRM = "confirm";
    private static final String ANSWER = "answer";
    private static final String DISPUTE = "dispute";
    private static final String DISPUTE_ISSUE_PREFIX = "dispute-issue-";
    private static final String OWNER_MANAGER_RIGHT = "owner-right";
    private static final String OWNER_REPORT_RIGHT = "owner-confirm";
    private static final String OWNER_NEEDS_CONTEXT = "owner-context";
    private static final String QUESTIONS_AI = "AI";
    private static final String QUESTIONS_PENDING_AI = "PENDING_AI";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final List<ManagerReportReviewStatus> TEXT_PENDING_STATUSES = List.of(
            ManagerReportReviewStatus.QUESTION_PENDING,
            ManagerReportReviewStatus.PLAN_PENDING,
            ManagerReportReviewStatus.DISPUTE_PENDING,
            ManagerReportReviewStatus.READING
    );

    private final ManagerReportReviewSessionRepository sessionRepository;
    private final ManagerReportReviewEventRepository eventRepository;
    private final ManagerReportReviewQualityService qualityService;
    private final ManagerSummaryFormatter formatter;
    private final TelegramService telegramService;
    private final NotificationMediaDeliveryService notificationMediaDeliveryService;
    private final UserService userService;
    private final ManagerRepository managerRepository;
    private final AppSettingService appSettingService;
    private final ManagerReportReviewOwnerNotificationService ownerNotificationService;
    private final ManagerReportReviewAccessPolicy accessPolicy;
    private final ManagerReportReviewAdminService adminService;
    private final ManagerReportReviewAiAvailabilityService aiAvailabilityService;
    private final ManagerReportReviewIssueService issueService;
    private final ManagerReportReviewTaskContextService taskContextService;

    public boolean enabled() {
        return appSettingService.getBoolean("manager.report-review.enabled", true);
    }

    public boolean enabledFor(Manager manager) {
        return enabled() && manager != null && manager.isReportReviewEnabled();
    }

    public static List<List<InlineKeyboardButton>> initialKeyboard(Long reviewId) {
        return List.of(List.of(button("📖 Изучить отчёт", STUDY, reviewId)));
    }

    public static List<List<InlineKeyboardButton>> continueKeyboard(Long reviewId) {
        return List.of(List.of(button("Продолжить проверку", START, reviewId)));
    }

    public static List<List<InlineKeyboardButton>> disputeKeyboard(Long reviewId) {
        return List.of(List.of(button("Оспорить неточность", DISPUTE, reviewId)));
    }

    public static List<List<InlineKeyboardButton>> readingKeyboard(Long reviewId) {
        return List.of(
                List.of(button("✅ Подтвердить прочтение", CONFIRM, reviewId)),
                List.of(button("⚖️ Сообщить о неточности", DISPUTE, reviewId))
        );
    }

    public static List<List<InlineKeyboardButton>> answerKeyboard(Long reviewId, boolean retry) {
        return List.of(List.of(button(retry ? "✍️ Ответить ещё раз" : "✍️ Ответить", ANSWER, reviewId)));
    }

    public static List<List<InlineKeyboardButton>> ownerDecisionKeyboard(Long reviewId) {
        return List.of(
                List.of(button("✅ Менеджер прав", OWNER_MANAGER_RIGHT, reviewId)),
                List.of(button("⚠️ Замечание верно", OWNER_REPORT_RIGHT, reviewId)),
                List.of(button("🔎 Недостаточно данных", OWNER_NEEDS_CONTEXT, reviewId))
        );
    }

    private static List<List<InlineKeyboardButton>> issueSelectionKeyboard(
            Long reviewId,
            List<ManagerReportReviewIssue> issues
    ) {
        return issues.stream()
                .map(issue -> List.of(button(
                        buttonTitle(issue),
                        DISPUTE_ISSUE_PREFIX + issue.getId(),
                        reviewId
                )))
                .toList();
    }

    @Transactional
    public boolean deliver(
            Manager manager,
            ManagerDailySummaryResponse summary
    ) {
        if (manager == null || manager.getId() == null || manager.getUser() == null
                || manager.getUser().getId() == null || manager.getUser().getTelegramChatId() == null
                || summary == null || summary.date() == null) {
            return false;
        }
        if (!enabledFor(manager)) {
            return false;
        }
        ManagerReportReviewSession review = sessionRepository
                .findBySummaryDateAndManager_IdAndTestModeFalse(summary.date(), manager.getId())
                .orElseGet(ManagerReportReviewSession::new);
        if (review.getDeliveredAt() != null) {
            return false;
        }

        User user = manager.getUser();
        Long recipientChatId = recipientChatId(manager);
        if (recipientChatId == null) {
            return false;
        }
        warnAboutMissingAuditGroup(manager, recipientChatId);
        return prepareAndSend(review, manager, summary, user, recipientChatId, false);
    }

    @Transactional
    public Optional<ManagerReportReviewSession> deliverTest(
            User tester,
            Manager sourceManager,
            ManagerDailySummaryResponse summary
    ) {
        if (!enabledFor(sourceManager) || tester == null || tester.getId() == null
                || tester.getTelegramChatId() == null || sourceManager == null
                || sourceManager.getId() == null || summary == null || summary.date() == null) {
            return Optional.empty();
        }
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        boolean sent = prepareAndSend(
                review,
                sourceManager,
                summary,
                tester,
                tester.getTelegramChatId(),
                true
        );
        return sent ? Optional.of(review) : Optional.empty();
    }

    private boolean prepareAndSend(
            ManagerReportReviewSession review,
            Manager manager,
            ManagerDailySummaryResponse summary,
            User recipientUser,
            Long recipientChatId,
            boolean testMode
    ) {
        ManagerFormattedReport report = formatter.formatPersonal(summary);
        review.setSummaryDate(summary.date());
        review.setManager(manager);
        review.setManagerUserId(recipientUser.getId());
        review.setManagerName(displayName(
                testMode ? null : recipientUser,
                summary.managerName()
        ));
        review.setTestMode(testMode);
        review.setTestOwnerUserId(testMode ? recipientUser.getId() : null);
        review.setTestRunId(testMode ? testRunId() : 0L);
        review.setRecipientChatId(recipientChatId);
        review.setStatus(ManagerReportReviewStatus.DELIVERED);
        review.setReportSnapshot(report.html());
        review.setReportRichSnapshot(report.richHtml());
        review.setQuestionsContext(report.questionContext());
        review.setCurrentQuestionIndex(0);
        review.setMinimumReadSeconds(minimumReadSeconds(report.html(), testMode));
        int issueCount = issueCount(summary);
        review.setIssueCount(issueCount);
        List<ManagerReportReviewQualityService.ReviewQuestion> preparedQuestions = List.of();
        if (issueCount > 0) {
            ManagerReportReviewQualityService.QuestionGeneration generation =
                    qualityService.generateQuestions(
                            taskContextService.refresh(report.questionContext()),
                            issueCount
                    );
            review.setQuestionsJson(qualityService.questionsJson(generation.questions()));
            review.setQuestionsSource(generation.aiVerified() ? QUESTIONS_AI : QUESTIONS_PENDING_AI);
            if (generation.aiVerified()) {
                preparedQuestions = generation.questions();
                review.setIssueCount(generation.questions().size());
            }
        } else {
            review.setQuestionsJson("[]");
            review.setQuestionsSource(QUESTIONS_AI);
        }
        review = sessionRepository.save(review);
        issueService.ensureIssues(review, preparedQuestions);

        sendAuditMedia(review, recipientUser, recipientChatId);

        LocalDateTime now = LocalDateTime.now();
        if (review.getIssueCount() == 0) {
            Optional<Integer> messageId = telegramService.sendRichMessageWithInlineKeyboardMessageId(
                    recipientChatId,
                    noIssuesMessage(review),
                    List.of()
            );
            boolean sent = messageId.isPresent() || telegramService.sendMessage(
                    recipientChatId,
                    testMode
                            ? "🧪 <b>Тестовый аудит завершён автоматически</b>\n\n"
                            + "В выбранном отчёте нет замечаний. Тестовая попытка сохранена "
                            + "без влияния на доступы и показатели."
                            : "🌟 <b>Вы молодец!</b>\n\nЗа день проблем и замечаний не обнаружено. "
                            + "Отчёт принят автоматически.",
                    "HTML"
            );
            if (!sent) {
                sessionRepository.delete(review);
                return false;
            }
            review.setTelegramMessageId(messageId.orElse(null));
            review.setDeliveredAt(now);
            review.setCompletedAt(now);
            review.setAutoCompleted(true);
            review.setAnswerQuality("NO_ISSUES");
            review.setAnswerQualityReason("Проблем и замечаний за день не обнаружено");
            review.setStatus(ManagerReportReviewStatus.COMPLETED);
            sessionRepository.save(review);
            event(review, "DELIVERED", null, "SYSTEM", deliverySource(recipientChatId),
                    testMode
                            ? "Тестовый отчёт доставлен администратору"
                            : "Отчёт доставлен в служебную группу менеджера");
            event(review, "AUTO_COMPLETED", null, "SYSTEM", "telegram",
                    testMode
                            ? "Тестовый отчёт принят автоматически: замечаний нет"
                            : "Отчёт принят автоматически: замечаний нет");
            if (!testMode) {
                accessPolicy.invalidate(review.getManagerUserId());
            }
            return true;
        }

        Optional<Integer> messageId = telegramService.sendRichMessageWithInlineKeyboardMessageId(
                recipientChatId,
                collapsedReport(review),
                initialKeyboard(review.getId())
        );
        boolean sent = messageId.isPresent();
        if (!sent) {
            Optional<Integer> fallbackId = telegramService.sendMessageWithInlineKeyboardMessageId(
                    recipientChatId,
                    initialFallbackMessage(review),
                    "HTML",
                    initialKeyboard(review.getId())
            );
            messageId = fallbackId;
            sent = fallbackId.isPresent();
        }
        if (!sent) {
            sessionRepository.delete(review);
            return false;
        }
        review.setTelegramMessageId(messageId.orElse(null));
        review.setDeliveredAt(now);
        sessionRepository.save(review);
        event(review, "DELIVERED", null, "SYSTEM", deliverySource(recipientChatId),
                testMode
                        ? "Тестовый отчёт доставлен администратору"
                        : "Отчёт доставлен в служебную группу менеджера");
        if (!testMode) {
            accessPolicy.invalidate(review.getManagerUserId());
        }
        return true;
    }

    private void sendAuditMedia(
            ManagerReportReviewSession review,
            User recipientUser,
            Long recipientChatId
    ) {
        String eventCode = review.getIssueCount() == 0
                ? NotificationMediaEventCatalog.MANAGER_TEAM_PROGRESS_GROWING.code()
                : NotificationMediaEventCatalog.MANAGER_TEAM_PROGRESS_SLOWED.code();
        try {
            notificationMediaDeliveryService.sendMediaOnly(
                    eventCode,
                    recipientChatId,
                    recipientUser.getId(),
                    "📘 <b>Персональный разбор готов</b>\n\nЖека подготовил аудит за день.",
                    "HTML"
            );
        } catch (RuntimeException exception) {
            log.warn("Не удалось отправить картинку персонального разбора managerUserId={}: {}",
                    recipientUser.getId(), exception.getMessage());
        }
    }

    @Transactional
    public Optional<String> handle(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.getData() == null
                || !callbackQuery.getData().startsWith(CALLBACK_PREFIX)) {
            return Optional.empty();
        }
        CallbackCommand command = parse(callbackQuery.getData());
        if (command == null) return Optional.of("Команда не распознана");
        Long telegramUserId = callbackQuery.getFrom() == null ? null : callbackQuery.getFrom().getId();
        Long callbackChatId = callbackQuery.getMessage() == null
                ? null
                : callbackQuery.getMessage().getChatId();
        User actor = telegramUserId == null
                ? null
                : userService.findByChatId(telegramUserId).filter(User::isActive).orElse(null);
        ManagerReportReviewSession review = sessionRepository.findForUpdateById(command.reviewId()).orElse(null);
        if (ownerDecision(command.action())) {
            if (!canOwnerResolve(review, actor, telegramUserId, callbackChatId)) {
                return Optional.of("Решение может принять только владелец или администратор");
            }
            String action = OWNER_MANAGER_RIGHT.equals(command.action())
                    ? ManagerReportReviewAdminService.REPORT_INCORRECT
                    : OWNER_REPORT_RIGHT.equals(command.action())
                            ? ManagerReportReviewAdminService.REPORT_CONFIRMED
                            : ManagerReportReviewAdminService.REPORT_NEEDS_CONTEXT;
            adminService.resolveDispute(
                    review.getId(),
                    action,
                    "Решение принято владельцем в Telegram",
                    actor
            );
            return Optional.of(OWNER_MANAGER_RIGHT.equals(command.action())
                    ? "Ошибка отчёта подтверждена"
                    : OWNER_REPORT_RIGHT.equals(command.action())
                            ? "Замечание подтверждено"
                            : "Запрошен дополнительный контекст");
        }
        if (!canAccess(review, actor, telegramUserId, callbackChatId)) {
            return Optional.of("Этот разбор назначен другому менеджеру");
        }
        if (command.action().startsWith(DISPUTE_ISSUE_PREFIX)) {
            Long issueId = parseIssueId(command.action());
            if (issueId == null) {
                return Optional.of("Замечание не распознано");
            }
            return Optional.of(startDispute(review, actor, issueId));
        }
        if (DISPUTE.equals(command.action())) {
            return Optional.of(startDisputeSelection(review));
        }
        if (review.getStatus() == ManagerReportReviewStatus.COMPLETED) {
            telegramService.sendMessageWithInlineKeyboard(
                    review.getRecipientChatId(),
                    review.isAutoCompleted()
                            ? "🌟 Отчёт уже принят автоматически: замечаний нет."
                            : "✅ Проверка уже завершена. Отчёт принят.",
                    "HTML",
                    disputeKeyboard(review.getId())
            );
            return Optional.of("Отчёт уже принят");
        }
        if (review.getStatus() == ManagerReportReviewStatus.DISPUTED) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "⚖️ Спор находится на рассмотрении. Если в аудите остались другие замечания, "
                            + "бот продолжит их проверку после решения владельца.",
                    "HTML"
            );
            return Optional.of("Спор уже передан владельцу");
        }
        if (STUDY.equals(command.action())) {
            return Optional.of(startReading(review, actor));
        }
        if (CONFIRM.equals(command.action())) {
            return Optional.of(confirmReading(review, actor));
        }
        if (ANSWER.equals(command.action())) {
            promptAnswer(review);
            return Optional.of("Напишите ответ на вопрос");
        }
        if (START.equals(command.action())) {
            return Optional.of(continueFlow(review, actor));
        }
        return Optional.of("Команда не распознана");
    }

    @Transactional
    public boolean handleTextMessage(long chatId, User user, String messageText) {
        return handleTextMessage(chatId, user, messageText, null);
    }

    @Transactional
    public boolean handleTextMessage(long chatId, User user, String messageText, Integer replyToMessageId) {
        if (user == null || user.getId() == null || !user.isActive() || clean(messageText).isBlank()) {
            return false;
        }
        ManagerReportReviewSession review = sessionRepository
                .findFirstByManagerUserIdAndRecipientChatIdAndStatusInOrderByCreatedAtDesc(
                        user.getId(),
                        chatId,
                        TEXT_PENDING_STATUSES
                )
                .orElse(null);
        if (review == null) return false;
        if (review.getStatus() == ManagerReportReviewStatus.READING) {
            telegramService.sendMessageWithInlineKeyboard(
                    review.getRecipientChatId(),
                    "Это сообщение не засчитано как ответ: системные вопросы ещё не выдавались. "
                            + "Сначала подтвердите прочтение отчёта.",
                    "HTML",
                    readingKeyboard(review.getId())
            );
            return true;
        }
        if ((review.getStatus() == ManagerReportReviewStatus.QUESTION_PENDING || isGroupChat(chatId))
                && (review.getReplyPromptMessageId() == null
                || !review.getReplyPromptMessageId().equals(replyToMessageId))) {
            telegramService.sendMessageWithInlineKeyboard(
                    review.getRecipientChatId(),
                    "Чтобы ответ засчитался именно этому менеджеру и текущему шагу, "
                            + "используйте кнопку <b>«Ответить»</b> или ответьте на последнее сообщение бота.",
                    "HTML",
                    review.getStatus() == ManagerReportReviewStatus.QUESTION_PENDING
                            ? answerKeyboard(review.getId(), false)
                            : review.getStatus() == ManagerReportReviewStatus.DISPUTE_PENDING
                                    ? disputeKeyboard(review.getId())
                                    : continueKeyboard(review.getId())
            );
            return true;
        }
        return switch (review.getStatus()) {
            case QUESTION_PENDING -> handleQuestionAnswer(review, user, messageText);
            case PLAN_PENDING -> handleActionPlan(review, user, messageText);
            case DISPUTE_PENDING -> handleDispute(review, user, messageText);
            default -> false;
        };
    }

    @Transactional
    public Optional<String> handleGroupCommand(long chatId, Long actorTelegramId, String messageText) {
        if (chatId >= 0 || !isAuditGroupCommand(messageText)) {
            return Optional.empty();
        }
        User actor = actorTelegramId == null
                ? null
                : userService.findByChatId(actorTelegramId).filter(User::isActive).orElse(null);
        if (actor == null || actor.getId() == null) {
            return Optional.of("Сначала привяжите личный Telegram к своему аккаунту.");
        }
        Manager manager = managerRepository.findByUserId(actor.getId()).orElse(null);
        if (manager == null) {
            return Optional.of("Привязать группу может только менеджер, для которого формируется аудит.");
        }
        Manager occupied = managerRepository.findByAuditTelegramGroupChatId(chatId).orElse(null);
        if (occupied != null && !occupied.getId().equals(manager.getId())) {
            return Optional.of("Эта группа уже привязана к другому менеджеру.");
        }
        manager.setAuditTelegramGroupChatId(chatId);
        managerRepository.save(manager);
        return Optional.of("✅ <b>Группа привязана к аудиту менеджера "
                + escape(displayName(actor, null))
                + ".</b>\n\nСледующий персональный отчёт, вопросы, ответы и итог проверки "
                + "будут видны здесь менеджеру и владельцу.");
    }

    @Transactional
    public boolean handleGroupTextMessage(
            long chatId,
            Long actorTelegramId,
            String messageText,
            Integer replyToMessageId
    ) {
        if (chatId >= 0 || actorTelegramId == null) {
            return false;
        }
        User actor = userService.findByChatId(actorTelegramId).filter(User::isActive).orElse(null);
        return handleTextMessage(chatId, actor, messageText, replyToMessageId);
    }

    private String startReading(ManagerReportReviewSession review, User actor) {
        if (review.getStartedAt() != null) {
            return "Отчёт уже открыт, продолжайте изучение";
        }
        boolean opened = review.getTelegramMessageId() != null
                && telegramService.editRichMessage(
                review.getRecipientChatId(),
                review.getTelegramMessageId(),
                expandedReport(review),
                readingKeyboard(review.getId())
        );
        if (!opened) {
            Optional<Integer> messageId = telegramService.sendRichMessageWithInlineKeyboardMessageId(
                    review.getRecipientChatId(),
                    expandedReport(review),
                    readingKeyboard(review.getId())
            );
            if (messageId.isEmpty()) {
                messageId = telegramService.sendMessageWithInlineKeyboardMessageId(
                        review.getRecipientChatId(),
                        expandedFallbackMessage(review),
                        "HTML",
                        readingKeyboard(review.getId())
                );
            }
            if (messageId.isPresent()) {
                review.setTelegramMessageId(messageId.get());
                opened = true;
            }
        }
        if (!opened) {
            return "Не удалось открыть отчёт. Нажмите «Изучить отчёт» ещё раз";
        }

        LocalDateTime now = LocalDateTime.now();
        review.setStartedAt(now);
        event(review, "READING_STARTED", actor.getId(), actorRole(review), "telegram",
                review.isTestMode()
                        ? "Администратор открыл тестовый отчёт для изучения"
                        : "Менеджер открыл отчёт для изучения");
        if (review.getDeadlineStartedAt() == null) {
            review.setDeadlineStartedAt(now);
            event(review, "DEADLINE_STARTED", actor.getId(), actorRole(review), "telegram",
                    review.isTestMode()
                            ? "Тестовый таймер прохождения запущен без ограничений доступа"
                            : "Трёхчасовой срок начат при открытии отчёта");
        }
        review.setStatus(ManagerReportReviewStatus.READING);
        sessionRepository.save(review);
        if (!review.isTestMode()) {
            accessPolicy.invalidate(review.getManagerUserId());
        }
        if (isGroupChat(review.getRecipientChatId())) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "👀 <b>" + escape(review.getManagerName()) + " начал(а) изучение отчёта.</b>\n"
                            + "С этого момента идёт трёхчасовой срок на прохождение проверки.",
                    "HTML"
            );
        }
        return "Отчёт открыт для изучения";
    }

    private String confirmReading(ManagerReportReviewSession review, User actor) {
        if (review.getStartedAt() == null || review.getStatus() == ManagerReportReviewStatus.DELIVERED) {
            String result = startReading(review, actor);
            return result.startsWith("Не удалось")
                    ? result
                    : "Сначала изучите открытый отчёт";
        }
        if (review.getReadingConfirmedAt() != null) {
            if (questionsPendingAi(review)) {
                sendAiUnavailable(review);
                return "Прочтение уже подтверждено. Продолжите проверку немного позже";
            }
            if (review.getStatus() == ManagerReportReviewStatus.QUESTION_PENDING) {
                telegramService.sendMessageWithInlineKeyboard(
                        review.getRecipientChatId(),
                        "✅ Прочтение уже подтверждено.\n\n"
                                + "Продолжите с вопроса, который бот отправил отдельным сообщением.",
                        "HTML",
                        answerKeyboard(review.getId(), false)
                );
                return "Прочтение уже подтверждено";
            }
            return "Прочтение уже подтверждено";
        }
        LocalDateTime now = LocalDateTime.now();
        long seconds = readSeconds(review, now);
        if (seconds < review.getMinimumReadSeconds()) {
            long remaining = review.getMinimumReadSeconds() - seconds;
            String message = "Для внимательного чтения осталось примерно " + humanWait(remaining);
            telegramService.sendMessageWithInlineKeyboard(
                    review.getRecipientChatId(),
                    "⏳ <b>Отчёт пока нельзя подтвердить</b>\n\n"
                            + message + ". После этого нажмите «Подтвердить прочтение» ещё раз.",
                    "HTML",
                    readingKeyboard(review.getId())
            );
            return message;
        }
        review.setReadingConfirmedAt(now);
        review.setReadSeconds(seconds);
        review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        sessionRepository.save(review);
        event(review, "READING_CONFIRMED", actor.getId(), actorRole(review), "telegram",
                "Прочтение подтверждено за " + seconds + " сек.");
        if (questionsPendingAi(review)) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "✅ Прочтение подтверждено. Формирую вопросы по замечаниям отчёта…",
                    "HTML"
            );
        }
        List<ManagerReportReviewQualityService.ReviewQuestion> questions = questions(review);
        if (questions.isEmpty()) {
            if (questionsPendingAi(review)) {
                sendAiUnavailable(review);
                return "DeepSeek временно недоступен. Проверка сохранена, трёхчасовой срок приостановлен";
            }
            completeReview(review, actor, "Проблемы для проверки отсутствуют");
            return "Отчёт принят";
        }
        if (sendQuestion(review, questions, "✅ Прочтение подтверждено.")) {
            return "Первый вопрос отправлен";
        }
        if (hasPendingQuestion(review, questions)) {
            notifyQuestionDeliveryFailure(review);
            return "Не удалось отправить вопрос. Повторите через кнопку «Продолжить проверку»";
        }
        waitForDisputeOrComplete(review, actor, "После подтверждения не осталось действующих вопросов");
        return issueService.hasUnresolvedDisputes(review)
                ? "Осталось дождаться решения по спору"
                : "Отчёт принят";
    }

    private String continueFlow(ManagerReportReviewSession review, User actor) {
        if (review.getStatus() == ManagerReportReviewStatus.DELIVERED
                || review.getStatus() == ManagerReportReviewStatus.READING) {
            startReading(review, actor);
            return "Отчёт открыт для изучения";
        }
        if (review.getStatus() == ManagerReportReviewStatus.PLAN_PENDING) {
            sessionRepository.save(review);
            sendPlanPrompt(review, "");
            return "Форма плана действий отправлена";
        }
        if (review.getStatus() == ManagerReportReviewStatus.QUESTION_PENDING) {
            List<ManagerReportReviewQualityService.ReviewQuestion> questions = questions(review);
            if (questions.isEmpty() && questionsPendingAi(review)) {
                sendAiUnavailable(review);
                return "Вопросы пока не сформированы. Повторите немного позже";
            }
            if (sendQuestion(review, questions, "")) {
                return "Вопрос отправлен";
            }
            if (hasPendingQuestion(review, questions)) {
                notifyQuestionDeliveryFailure(review);
                return "Не удалось отправить вопрос. Повторите немного позже";
            }
            waitForDisputeOrComplete(review, actor, "Действующих вопросов больше нет");
            return review.getStatus() == ManagerReportReviewStatus.COMPLETED
                    ? "Отчёт принят"
                    : "Осталось дождаться решения по спору";
        }
        return "Проверка уже находится на другом этапе";
    }

    private boolean handleQuestionAnswer(
            ManagerReportReviewSession review,
            User actor,
            String messageText
    ) {
        List<ManagerReportReviewQualityService.ReviewQuestion> questions =
                questions(review);
        if (questions.isEmpty()) {
            if (questionsPendingAi(review)) {
                sendAiUnavailable(review);
                return true;
            }
            return false;
        }
        int requestedIndex = review.getCurrentQuestionIndex();
        withdrawCompletedTaskQuestions(review, questions);
        if (!issueService.isPending(review, requestedIndex)) {
            if (!sendQuestion(review, questions, "")) {
                waitForDisputeOrComplete(review, actor, "Связанные задачи уже выполнены");
            }
            return true;
        }
        int index = nextQuestionIndex(review, questions, review.getCurrentQuestionIndex());
        if (index < 0) {
            waitForDisputeOrComplete(review, actor, "Действующих вопросов больше нет");
            return true;
        }
        review.setCurrentQuestionIndex(index);
        ManagerReportReviewQualityService.ReviewQuestion question = questions.get(index);
        List<String> previousAttempts =
                qualityService.previousAcceptedContext(review.getAnswersJson(), index);
        boolean fastPasteRisk = fastPasteRisk(review, messageText);
        ManagerReportReviewQualityService.Assessment assessment =
                qualityService.assessAnswer(
                        review.getReportSnapshot(),
                        question,
                        messageText,
                        false,
                        previousAttempts,
                        fastPasteRisk
                );
        if (assessmentUnavailable(assessment)) {
            aiAvailabilityService.pause(
                    review,
                    LocalDateTime.now(),
                    "telegram-answer",
                    assessment.reason()
            );
            review.setAnswersJson(qualityService.appendAnswer(
                    review.getAnswersJson(),
                    new ManagerReportReviewQualityService.ReviewAnswer(
                            index,
                            question.question(),
                            limit(clean(messageText), 2000),
                            false,
                            0,
                            assessment.reason(),
                            assessment.provider()
                    )
            ));
            review.setAnswerQuality("AI_UNAVAILABLE");
            review.setAnswerQualityReason(limit(assessment.reason(), 1000));
            sessionRepository.save(review);
            event(review, "ANSWER_CHECK_DEFERRED", actor.getId(), actorRole(review), assessment.provider(),
                    "Ответ сохранён до восстановления автоматической проверки");
            sendAiUnavailable(review);
            return true;
        }
        aiAvailabilityService.resume(review, LocalDateTime.now(), "telegram-answer");
        boolean authenticityReview = fastPasteRisk || assessment.authenticityRisk();
        boolean accepted = assessment.accepted() && !authenticityReview;
        String assessmentReason = fastPasteRisk
                ? "Длинный ответ был отправлен почти сразу после вопроса. Это не доказывает использование ИИ, "
                + "поэтому нужен короткий ответ своими словами."
                : assessment.reason();
        String clarificationQuestion = fastPasteRisk
                ? "Коротко, в 1–2 предложениях своими словами: что именно вы лично проверите или сделаете иначе?"
                : assessment.clarificationQuestion();
        String answerProvider = authenticityReview ? "authenticity-check" : assessment.provider();
        if (authenticityReview) {
            review.setSuspiciousAnswerCount(review.getSuspiciousAnswerCount() + 1);
            review.setAuditRequired(true);
            event(review, "ANSWER_AUTHENTICITY_CHECK", actor.getId(), actorRole(review), answerProvider,
                    "Запрошено короткое пояснение своими словами; автоматических выводов об использовании ИИ нет");
        }
        review.setAnswersJson(qualityService.appendAnswer(
                review.getAnswersJson(),
                new ManagerReportReviewQualityService.ReviewAnswer(
                        index,
                        question.question(),
                        limit(clean(messageText), 2000),
                        accepted,
                        assessment.score(),
                        assessmentReason,
                        answerProvider
                )
        ));
        review.setAnswerQuality(accepted ? "ACCEPTED" : "NEEDS_CLARIFICATION");
        review.setAnswerQualityReason(limit(assessmentReason, 1000));
        event(
                review,
                accepted ? "ANSWER_ACCEPTED" : "ANSWER_REJECTED",
                actor.getId(),
                actorRole(review),
                answerProvider,
                "Вопрос: " + question.question() + "\nОтвет: " + clean(messageText)
                        + "\nОценка: " + assessmentReason
        );
        if (!accepted) {
            review.setReplyPromptMessageId(null);
            sessionRepository.save(review);
            String clarification = clean(clarificationQuestion);
            if (!sendQuestion(
                    review,
                    questions,
                    "Ответ пока не принят: " + assessmentReason
                            + (clarification.isBlank() ? "" : "\n\nУточнение: " + clarification)
            )) {
                notifyQuestionDeliveryFailure(review);
            }
            return true;
        }
        issueService.markAnswered(review, index);
        int next = nextQuestionIndex(review, questions, index + 1);
        review.setCurrentQuestionIndex(next < 0 ? questions.size() : next);
        review.setReplyPromptMessageId(null);
        if (next >= 0) {
            sessionRepository.save(review);
            if (!sendQuestion(
                    review,
                    questions,
                    "✅ Ответ принят.\n" + assessment.reason()
            )) {
                notifyQuestionDeliveryFailure(review);
            }
            return true;
        }
        waitForDisputeOrComplete(review, actor, assessment.reason());
        return true;
    }

    private boolean handleActionPlan(
            ManagerReportReviewSession review,
            User actor,
            String messageText
    ) {
        ManagerReportReviewQualityService.ReviewQuestion planQuestion =
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Что конкретно вы измените в следующую смену и как проверите результат?",
                        List.of("конкретное изменение", "способ проверки результата")
                );
        ManagerReportReviewQualityService.Assessment assessment =
                qualityService.assessAnswer(review.getReportSnapshot(), planQuestion, messageText, true);
        if (assessmentUnavailable(assessment)) {
            aiAvailabilityService.pause(
                    review,
                    LocalDateTime.now(),
                    "telegram-plan",
                    assessment.reason()
            );
            sendAiUnavailable(review);
            return true;
        }
        aiAvailabilityService.resume(review, LocalDateTime.now(), "telegram-plan");
        event(
                review,
                assessment.accepted() ? "PLAN_ACCEPTED" : "PLAN_REJECTED",
                actor.getId(),
                actorRole(review),
                assessment.provider(),
                "План: " + clean(messageText) + "\nОценка: " + assessment.reason()
        );
        if (!assessment.accepted()) {
            review.setAnswerQuality("NEEDS_CLARIFICATION");
            review.setAnswerQualityReason(limit(assessment.reason(), 1000));
            sessionRepository.save(review);
            sendPlanPrompt(
                    review,
                    "План пока слишком общий: " + assessment.reason()
                            + "\n\n" + clean(assessment.clarificationQuestion())
            );
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        review.setActionPlan(limit(clean(messageText), 2000));
        review.setAnswerQuality(assessment.score() >= 70 ? "ACCEPTED" : "NEEDS_OWNER_REVIEW");
        review.setAnswerQualityReason(limit(assessment.reason(), 1000));
        review.setCompletedAt(now);
        review.setStatus(ManagerReportReviewStatus.COMPLETED);
        boolean accessRestored = review.getRestrictedAt() != null
                && review.getRestrictionReleasedAt() == null;
        if (accessRestored) {
            review.setRestrictionReleasedAt(now);
        }
        sessionRepository.save(review);
        if (accessRestored) {
            event(review, "RESTRICTION_RELEASED", actor.getId(), "MANAGER", "telegram",
                    "План действий принят, доступ восстановлен автоматически");
        }
        if (!review.isTestMode()) {
            accessPolicy.invalidate(review.getManagerUserId());
        }
        String pace = review.getReadSeconds() < review.getMinimumReadSeconds()
                ? "\n\n⚠️ Разбор пройден быстрее расчётного времени чтения. Это видно владельцу вместе с ответами."
                : "";
        telegramService.sendMessageWithInlineKeyboard(
                review.getRecipientChatId(),
                (review.isTestMode()
                        ? "🧪 <b>Тестовый разбор завершён</b>\n\nПлан сохранён только в тестовой попытке."
                        : "✅ <b>Разбор завершён</b>\n\nПлан на следующую смену сохранён.")
                        + pace
                        + "\n\nЕсли в отчёте есть фактическая ошибка, её можно оспорить с объяснением.",
                "HTML",
                disputeKeyboard(review.getId())
        );
        return true;
    }

    private String startDisputeSelection(ManagerReportReviewSession review) {
        if (issueService.hasUnresolvedDisputes(review)) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "⚖️ По одному замечанию уже открыт спор. Дождитесь решения владельца; "
                            + "остальные вопросы аудита можно продолжать.",
                    "HTML"
            );
            return "Спор уже открыт";
        }
        List<ManagerReportReviewQualityService.ReviewQuestion> questions = questions(review);
        List<ManagerReportReviewIssue> issues = issueService.ensureIssues(review, questions);
        withdrawCompletedTaskQuestions(review, questions);
        issues = issueService.issues(review);
        List<ManagerReportReviewIssue> selectable = issueService.selectableIssues(review);
        if (selectable.isEmpty()) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "В этом аудите нет действующего замечания, которое можно оспорить.",
                    "HTML"
            );
            return "Нет замечаний для спора";
        }
        StringBuilder text = new StringBuilder(
                "⚖️ <b>Какое именно замечание вы оспариваете?</b>\n\n"
                        + "Спор будет относиться только к выбранному пункту. "
                        + "Остальные вопросы аудита останутся обязательными."
        );
        for (ManagerReportReviewIssue issue : issues) {
            if (selectable.stream().anyMatch(item -> item.getId().equals(issue.getId()))) {
                text.append("\n\n").append(issue.getQuestionIndex() + 1)
                        .append(". ").append(escape(issue.getQuestionText()));
            }
        }
        telegramService.sendMessageWithInlineKeyboard(
                review.getRecipientChatId(),
                text.toString(),
                "HTML",
                issueSelectionKeyboard(review.getId(), selectable)
        );
        return "Выберите замечание";
    }

    private String startDispute(
            ManagerReportReviewSession review,
            User actor,
            Long issueId
    ) {
        LocalDateTime now = LocalDateTime.now();
        ManagerReportReviewDispute dispute;
        try {
            dispute = issueService.beginDispute(review, issueId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "⚖️ " + escape(exception.getMessage()),
                    "HTML"
            );
            return exception.getMessage();
        }
        review.setStatus(ManagerReportReviewStatus.DISPUTE_PENDING);
        if (review.getStartedAt() == null) review.setStartedAt(now);
        if (review.getDeadlineStartedAt() == null) review.setDeadlineStartedAt(now);
        sessionRepository.save(review);
        event(review, "DISPUTE_REQUESTED", actor.getId(), actorRole(review), "telegram",
                "Выбрано замечание: " + dispute.getIssue().getTitle());
        Optional<Integer> promptId = sendReplyPrompt(
                review,
                "⚖️ Вы оспариваете:\n"
                        + dispute.getIssue().getQuestionText()
                        + "\n\nОпишите конкретно, что в этом замечании неверно.\n\n"
                        + "Укажите пример, фактическое событие и, если возможно, где это можно проверить. "
                        + "Остальные замечания останутся в проверке."
        );
        review.setReplyPromptMessageId(promptId.orElse(null));
        sessionRepository.save(review);
        return "Опишите неточность";
    }

    private boolean handleDispute(
            ManagerReportReviewSession review,
            User actor,
            String messageText
    ) {
        String dispute = clean(messageText);
        if (dispute.length() < 20) {
            Optional<Integer> promptId = sendReplyPrompt(
                    review,
                    "Нужно чуть подробнее: какой именно вывод неверен, что произошло фактически и где это проверить?"
            );
            promptId.ifPresent(review::setReplyPromptMessageId);
            sessionRepository.save(review);
            return true;
        }
        ManagerReportReviewDispute issueDispute;
        try {
            issueDispute = issueService.submitDispute(review, dispute);
        } catch (IllegalStateException exception) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "⚖️ " + escape(exception.getMessage()),
                    "HTML"
            );
            return true;
        }
        review.setDisputeText(limit(
                issueDispute.getIssue().getTitle() + "\n" + dispute,
                2000
        ));
        review.setDisputedAt(LocalDateTime.now());
        review.setAuditRequired(true);
        ManagerReportReviewStatus previousStatus = issueDispute.getPreviousSessionStatus();
        if (previousStatus == ManagerReportReviewStatus.COMPLETED) {
            review.setStatus(ManagerReportReviewStatus.COMPLETED);
        } else if (review.getReadingConfirmedAt() == null) {
            review.setStatus(ManagerReportReviewStatus.READING);
        } else if (issueService.hasPendingQuestions(review)) {
            review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        } else {
            review.setStatus(ManagerReportReviewStatus.DISPUTED);
        }
        boolean onlyWaitingForOwner = review.getReadingConfirmedAt() != null
                && !issueService.hasPendingQuestions(review);
        boolean accessPaused = onlyWaitingForOwner
                && review.getRestrictedAt() != null
                && review.getRestrictionReleasedAt() == null;
        if (accessPaused) review.setRestrictionReleasedAt(LocalDateTime.now());
        sessionRepository.save(review);
        event(review, "DISPUTED", actor.getId(), actorRole(review), "telegram",
                "Замечание: " + issueDispute.getIssue().getQuestionText() + "\nСпор: " + dispute);
        if (accessPaused) {
            event(review, "RESTRICTION_RELEASED", actor.getId(), "MANAGER", "telegram",
                    "Неоспоренных вопросов не осталось; ограничение приостановлено до решения владельца");
        }
        if (!review.isTestMode()) {
            accessPolicy.invalidate(review.getManagerUserId());
        }
        if (!review.isTestMode() && !isGroupChat(review.getRecipientChatId())) {
            ownerNotificationService.notifyDispute(review);
        }
        telegramService.sendMessageWithInlineKeyboard(
                review.getRecipientChatId(),
                "⚖️ <b>Спор по одному замечанию передан владельцу</b>\n\n"
                        + "Оспоренный пункт временно исключён. "
                        + remainingFlowText(review),
                "HTML",
                ownerDecisionKeyboard(review.getId())
        );
        continueAfterDispute(review, actor);
        return true;
    }

    private void continueAfterDispute(ManagerReportReviewSession review, User actor) {
        if (review.getStatus() == ManagerReportReviewStatus.COMPLETED) {
            return;
        }
        if (review.getReadingConfirmedAt() == null) {
            review.setStatus(ManagerReportReviewStatus.READING);
            sessionRepository.save(review);
            telegramService.sendMessageWithInlineKeyboard(
                    review.getRecipientChatId(),
                    "Продолжите аудит: внимательно изучите оставшиеся замечания и подтвердите прочтение.",
                    "HTML",
                    readingKeyboard(review.getId())
            );
            return;
        }
        List<ManagerReportReviewQualityService.ReviewQuestion> questions = questions(review);
        if (sendQuestion(review, questions, "⚖️ Оспоренный пункт пропущен. Проверка остальных замечаний продолжается.")) {
            review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
            sessionRepository.save(review);
            return;
        }
        if (hasPendingQuestion(review, questions)) {
            review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
            sessionRepository.save(review);
            notifyQuestionDeliveryFailure(review);
            return;
        }
        review.setStatus(ManagerReportReviewStatus.DISPUTED);
        sessionRepository.save(review);
        telegramService.sendMessage(
                review.getRecipientChatId(),
                "Остальных вопросов сейчас нет. Завершение аудита ожидает решения владельца по спору.",
                "HTML"
        );
    }

    private String remainingFlowText(ManagerReportReviewSession review) {
        if (review.getStatus() == ManagerReportReviewStatus.COMPLETED) {
            return "Ранее пройденные ответы сохранены; владелец проверит только выбранный пункт.";
        }
        if (review.getReadingConfirmedAt() == null) {
            return "Теперь подтвердите прочтение и продолжите проверку остальных замечаний.";
        }
        if (issueService.hasPendingQuestions(review)) {
            return "Следующий действующий вопрос будет отправлен отдельно.";
        }
        return "Других вопросов нет; завершение ожидает решения владельца.";
    }

    private int nextQuestionIndex(
            ManagerReportReviewSession review,
            List<ManagerReportReviewQualityService.ReviewQuestion> questions,
            int fromIndex
    ) {
        if (questions == null || questions.isEmpty()) return -1;
        issueService.ensureIssues(review, questions);
        return issueService.nextPending(review, fromIndex)
                .map(ManagerReportReviewIssue::getQuestionIndex)
                .filter(index -> index >= 0 && index < questions.size())
                .orElse(-1);
    }

    private void waitForDisputeOrComplete(
            ManagerReportReviewSession review,
            User actor,
            String reason
    ) {
        if (!issueService.hasUnresolvedDisputes(review)) {
            completeReview(review, actor, reason);
            return;
        }
        review.setStatus(ManagerReportReviewStatus.DISPUTED);
        review.setReplyPromptMessageId(null);
        review.setCurrentQuestionIndex(questions(review).size());
        sessionRepository.save(review);
        if (!review.isTestMode()) {
            accessPolicy.invalidate(review.getManagerUserId());
        }
        telegramService.sendMessage(
                review.getRecipientChatId(),
                "✅ Все неоспоренные вопросы пройдены.\n\n"
                        + "Аудит пока не завершён: ожидается решение владельца по спорному замечанию.",
                "HTML"
        );
    }

    private boolean sendQuestion(
            ManagerReportReviewSession review,
            List<ManagerReportReviewQualityService.ReviewQuestion> questions,
            String prefix
    ) {
        if (questions == null || questions.isEmpty()) return false;
        issueService.ensureIssues(review, questions);
        withdrawCompletedTaskQuestions(review, questions);
        int index = nextQuestionIndex(review, questions, review.getCurrentQuestionIndex());
        if (index < 0) return false;
        review.setCurrentQuestionIndex(index);
        ManagerReportReviewQualityService.ReviewQuestion question = questions.get(index);
        QuestionProgress progress = questionProgress(review, questions, index);
        String text = (clean(prefix).isBlank()
                ? ""
                : escape(clean(prefix)) + "\n\n")
                + "❓ <b>Вопрос " + progress.position() + " из " + progress.total() + "</b>\n\n"
                + escape(question.question())
                + answerDirectionsHtml()
                + "\n\nКороткий формат, <b>до " + maximumAnswerCharacters() + " символов</b>: "
                + "<i>что произошло → ваш вывод → как вы поступите в похожей ситуации</i>.";
        Optional<Integer> messageId = telegramService.sendMessageWithInlineKeyboardMessageId(
                review.getRecipientChatId(),
                text,
                "HTML",
                answerKeyboard(review.getId(), !clean(prefix).isBlank() && prefix.contains("не принят"))
        );
        if (messageId.isEmpty()) {
            log.warn(
                    "Manager review question was not delivered reviewId={} chatId={} questionIndex={}",
                    review.getId(),
                    review.getRecipientChatId(),
                    index
            );
            return false;
        }
        review.setQuestionMessageId(messageId.orElse(null));
        review.setReplyPromptMessageId(null);
        review.setQuestionSentAt(LocalDateTime.now());
        sessionRepository.save(review);
        return true;
    }

    private boolean hasPendingQuestion(
            ManagerReportReviewSession review,
            List<ManagerReportReviewQualityService.ReviewQuestion> questions
    ) {
        return questions != null
                && !questions.isEmpty()
                && nextQuestionIndex(review, questions, review.getCurrentQuestionIndex()) >= 0;
    }

    private void notifyQuestionDeliveryFailure(ManagerReportReviewSession review) {
        telegramService.sendMessageWithInlineKeyboard(
                review.getRecipientChatId(),
                "⚠️ <b>Вопрос не удалось отправить</b>\n\n"
                        + "Прочтение уже подтверждено, прогресс сохранён. "
                        + "Нажмите «Продолжить проверку», чтобы повторить отправку вопроса.",
                "HTML",
                continueKeyboard(review.getId())
        );
    }

    private void promptAnswer(ManagerReportReviewSession review) {
        if (review.getStatus() != ManagerReportReviewStatus.QUESTION_PENDING) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "Сначала откройте отчёт и подтвердите прочтение.",
                    "HTML"
            );
            return;
        }
        List<ManagerReportReviewQualityService.ReviewQuestion> questions = questions(review);
        if (questions.isEmpty()) {
            if (questionsPendingAi(review)) {
                sendAiUnavailable(review);
            }
            return;
        }
        withdrawCompletedTaskQuestions(review, questions);
        int index = nextQuestionIndex(review, questions, review.getCurrentQuestionIndex());
        if (index < 0) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "Действующих вопросов сейчас нет. Возможно, ожидается решение владельца по спору.",
                    "HTML"
            );
            return;
        }
        review.setCurrentQuestionIndex(index);
        ManagerReportReviewQualityService.ReviewQuestion question = questions.get(index);
        QuestionProgress progress = questionProgress(review, questions, index);
        String text = "✍️ Ответьте на вопрос " + progress.position() + " из " + progress.total() + ":\n\n"
                + question.question()
                + answerDirectionsPlain()
                + "\n\nКороткий формат, до " + maximumAnswerCharacters()
                + " символов: что произошло → ваш вывод → как вы поступите в похожей ситуации.";
        Optional<Integer> promptId = sendReplyPrompt(review, text);
        review.setReplyPromptMessageId(promptId.orElse(null));
        review.setQuestionSentAt(LocalDateTime.now());
        sessionRepository.save(review);
    }

    private QuestionProgress questionProgress(
            ManagerReportReviewSession review,
            List<ManagerReportReviewQualityService.ReviewQuestion> questions,
            int questionIndex
    ) {
        List<Integer> activeIndexes = issueService.issues(review).stream()
                .filter(issue -> issue.getStatus() != ManagerReportReviewIssueStatus.WITHDRAWN)
                .map(ManagerReportReviewIssue::getQuestionIndex)
                .filter(index -> index >= 0 && index < questions.size())
                .distinct()
                .sorted()
                .toList();
        if (activeIndexes.isEmpty()) {
            return new QuestionProgress(questionIndex + 1, questions.size());
        }
        int position = activeIndexes.indexOf(questionIndex);
        return new QuestionProgress(position < 0 ? 1 : position + 1, activeIndexes.size());
    }

    private void withdrawCompletedTaskQuestions(
            ManagerReportReviewSession review,
            List<ManagerReportReviewQualityService.ReviewQuestion> questions
    ) {
        int withdrawn = issueService.withdrawResolvedSourceIssues(review, questions);
        if (withdrawn <= 0) return;
        telegramService.sendMessage(
                review.getRecipientChatId(),
                "✅ <b>Аудит обновлён</b>\n\n"
                        + (withdrawn == 1
                        ? "Одна связанная задача уже выполнена после формирования отчёта. "
                        : "Связанные задачи уже выполнены после формирования отчёта: " + withdrawn + ". ")
                        + "Вопрос по ней снят автоматически.",
                "HTML"
        );
    }

    private record QuestionProgress(int position, int total) {
    }

    private void sendPlanPrompt(ManagerReportReviewSession review, String prefix) {
        Optional<Integer> promptId = sendReplyPrompt(
                review,
                (clean(prefix).isBlank() ? "" : clean(prefix) + "\n\n")
                        + "🎯 Последний шаг\n\n"
                        + "Что конкретно вы измените в следующую смену и как проверите, что проблема не повторилась?"
        );
        review.setReplyPromptMessageId(promptId.orElse(null));
        review.setQuestionSentAt(LocalDateTime.now());
        sessionRepository.save(review);
    }

    private Optional<Integer> sendReplyPrompt(ManagerReportReviewSession review, String text) {
        Long chatId = review == null ? null : review.getRecipientChatId();
        if (chatId == null) {
            return Optional.empty();
        }
        if (isGroupChat(chatId)) {
            return telegramService.sendMessageWithInlineKeyboardMessageId(
                    chatId,
                    clean(text)
                            + "\n\nЧтобы ответ засчитался, нажмите «Ответить» на это сообщение.",
                    null,
                    List.of()
            );
        }
        return telegramService.sendForceReplyMessageId(chatId, text);
    }

    private void completeReview(
            ManagerReportReviewSession review,
            User actor,
            String finalReason
    ) {
        LocalDateTime now = LocalDateTime.now();
        long answeredIssues = issueService.answeredCount(review);
        long withdrawnIssues = issueService.withdrawnCount(review);
        review.setCompletedAt(now);
        review.setCurrentQuestionIndex(questions(review).size());
        review.setReplyPromptMessageId(null);
        review.setStatus(ManagerReportReviewStatus.COMPLETED);
        review.setAnswerQuality(withdrawnIssues > 0 ? "CORRECTED_AND_PASSED" : "ACCEPTED");
        review.setAnswerQualityReason(limit(clean(finalReason), 1000));
        boolean accessRestored = review.getRestrictedAt() != null
                && review.getRestrictionReleasedAt() == null;
        if (accessRestored) review.setRestrictionReleasedAt(now);
        sessionRepository.save(review);
        event(review, "REVIEW_COMPLETED", actor == null ? null : actor.getId(), actorRole(review),
                "telegram", review.isTestMode()
                        ? "Тестовый аудит завершён; действующие вопросы отвечены правильно"
                        : "Все действующие вопросы пройдены правильно");
        if (accessRestored) {
            event(review, "RESTRICTION_RELEASED", actor == null ? null : actor.getId(), "MANAGER",
                    "telegram", "Все вопросы пройдены, доступ восстановлен автоматически");
        }
        if (!review.isTestMode()) {
            accessPolicy.invalidate(review.getManagerUserId());
        }
        telegramService.sendMessageWithInlineKeyboard(
                review.getRecipientChatId(),
                (review.isTestMode()
                        ? "🧪 <b>Тестовый аудит завершён</b>\n\n"
                        + "Попытка сохранена в «Контроле менеджеров»: там доступны ответы, "
                        + "оценки DeepSeek и история событий.\n\n"
                        : "✅ <b>Отчёт принят</b>\n\n")
                        + "Вы изучили замечания и правильно ответили на "
                        + answeredIssues + " действующих вопросов."
                        + (withdrawnIssues > 0
                        ? "\n\nВладельцем снято некорректных замечаний: " + withdrawnIssues + "."
                        : "")
                        + (accessRestored
                        ? "\n\nДоступ ко всем рабочим разделам восстановлен автоматически."
                        : ""),
                "HTML",
                disputeKeyboard(review.getId())
        );
    }

    private List<ManagerReportReviewQualityService.ReviewQuestion> questions(
            ManagerReportReviewSession review
    ) {
        List<ManagerReportReviewQualityService.ReviewQuestion> questions =
                qualityService.readQuestions(review.getQuestionsJson());
        if (!questions.isEmpty()) return questions;
        if (!QUESTIONS_PENDING_AI.equalsIgnoreCase(clean(review.getQuestionsSource()))) {
            return questions;
        }
        ManagerReportReviewQualityService.QuestionGeneration generation =
                qualityService.generateQuestions(
                taskContextService.refresh(clean(review.getQuestionsContext()).isBlank()
                        ? review.getReportSnapshot()
                        : review.getQuestionsContext()),
                Math.max(0, review.getIssueCount())
        );
        if (!generation.aiVerified()) {
            aiAvailabilityService.pause(
                    review,
                    LocalDateTime.now(),
                    "question-generation",
                    generation.reason()
            );
            return List.of();
        }
        aiAvailabilityService.resume(review, LocalDateTime.now(), "question-generation");
        review.setQuestionsJson(qualityService.questionsJson(generation.questions()));
        review.setQuestionsSource(QUESTIONS_AI);
        review.setIssueCount(generation.questions().size());
        sessionRepository.save(review);
        issueService.ensureIssues(review, generation.questions());
        return generation.questions();
    }

    private boolean questionsPendingAi(ManagerReportReviewSession review) {
        return QUESTIONS_PENDING_AI.equalsIgnoreCase(clean(review.getQuestionsSource()));
    }

    private String answerDirectionsHtml() {
        return "\n\n🧭 <b>Подумайте:</b>"
                + "\n• что в ответе или действии было недостаточно;"
                + "\n• какой принцип работы здесь важен;"
                + "\n• как вы самостоятельно поступите в похожей ситуации."
                + "\n\n<i>Готовой формулировки нет — ответьте своими словами.</i>";
    }

    private String answerDirectionsPlain() {
        return "\n\nПодумайте:"
                + "\n• что в ответе или действии было недостаточно;"
                + "\n• какой принцип работы здесь важен;"
                + "\n• как вы самостоятельно поступите в похожей ситуации."
                + "\n\nГотовой формулировки нет — ответьте своими словами.";
    }

    private boolean assessmentUnavailable(ManagerReportReviewQualityService.Assessment assessment) {
        return assessment != null
                && assessment.score() == 0
                && clean(assessment.reason()).toLowerCase(java.util.Locale.ROOT).contains("недоступ");
    }

    private boolean fastPasteRisk(ManagerReportReviewSession review, String answer) {
        if (review.getQuestionSentAt() == null) return false;
        int minimumCharacters = Math.max(80, Math.min(500, appSettingService.getInt(
                "manager.report-review.fast-paste-min-characters",
                140
        )));
        int maximumSeconds = Math.max(3, Math.min(60, appSettingService.getInt(
                "manager.report-review.fast-paste-seconds",
                12
        )));
        long seconds = Math.max(
                0,
                Duration.between(review.getQuestionSentAt(), LocalDateTime.now()).toSeconds()
        );
        return clean(answer).length() >= minimumCharacters && seconds <= maximumSeconds;
    }

    private int maximumAnswerCharacters() {
        return Math.max(180, Math.min(1200, appSettingService.getInt(
                "manager.report-review.max-answer-characters",
                420
        )));
    }

    private void sendAiUnavailable(ManagerReportReviewSession review) {
        telegramService.sendMessageWithInlineKeyboard(
                review.getRecipientChatId(),
                "⏸ <b>Автоматическая проверка временно недоступна</b>\n\n"
                        + "Ваш прогресс сохранён. Трёхчасовой срок приостановлен и доступ "
                        + "не будет ограничен из-за сбоя DeepSeek. Нажмите «Продолжить проверку» немного позже.",
                "HTML",
                continueKeyboard(review.getId())
        );
    }

    private int issueCount(ManagerDailySummaryResponse summary) {
        if (summary == null) return 0;
        long explicit = Math.max(0, summary.problemCount());
        long open = Math.max(0, summary.overdueCount())
                + Math.max(0, summary.riskCount())
                + Math.max(0, summary.unansweredCount())
                + Math.max(0, summary.taskOtherOpen())
                + Math.max(0, summary.hardSlaBreachCount());
        return (int) Math.min(Integer.MAX_VALUE, Math.max(explicit, open));
    }

    private String collapsedReport(ManagerReportReviewSession review) {
        return (review.isTestMode()
                ? "<h2>🧪 Тестовый аудит · " + review.getSummaryDate().format(DATE) + "</h2>"
                + "<blockquote>Без блокировки доступа и без влияния на показатели менеджера</blockquote>"
                : "<h2>📘 Персональный отчёт · " + review.getSummaryDate().format(DATE) + "</h2>")
                + "<p>👤 <b>" + escape(review.getManagerName()) + "</b></p>"
                + "<p>Обнаружено вопросов для проверки: <b>" + review.getIssueCount() + "</b>.</p>"
                + "<blockquote>Текст отчёта станет доступен после нажатия кнопки.</blockquote>"
                + "<p>Нажмите <b>«Изучить отчёт»</b>, чтобы открыть полный отчёт "
                + "и начать отсчёт времени чтения.</p>";
    }

    private String expandedReport(ManagerReportReviewSession review) {
        String report = clean(review.getReportRichSnapshot());
        if (report.isBlank()) {
            report = "<h2>📘 Персональный отчёт</h2><p>"
                    + richPlain(review.getReportSnapshot()) + "</p>";
        }
        return (review.isTestMode()
                ? "<h2>🧪 Тестовый режим</h2>"
                + "<blockquote>Прохождение будет сохранено для анализа. "
                + "Рабочие доступы и статистика менеджеров не изменятся.</blockquote>"
                : "")
                + report
                + "<p><b>Следующий шаг:</b> внимательно изучите примеры, затем нажмите "
                + "«Подтвердить прочтение».</p>";
    }

    private String noIssuesMessage(ManagerReportReviewSession review) {
        return (review.isTestMode()
                ? "<h2>🧪 Тестовый аудит завершён автоматически</h2>"
                + "<blockquote>Тестовая попытка сохранена. Ограничения доступа не применяются.</blockquote>"
                : "<h2>🌟 Вы молодец!</h2>")
                + "<p>👤 <b>" + escape(review.getManagerName()) + "</b></p>"
                + "<p>За день проблем и замечаний не обнаружено. Отчёт принят автоматически.</p>"
                + "<p>Продолжайте сохранять такое же качество работы.</p>";
    }

    private String richPlain(String html) {
        String plain = clean(html)
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("\\n{3,}", "\n\n");
        return escape(plain).replace("\n", "<br>");
    }

    private String humanWait(long seconds) {
        if (seconds < 60) return seconds + " сек.";
        long minutes = (seconds + 59) / 60;
        return minutes + " мин.";
    }

    private void event(
            ManagerReportReviewSession review,
            String eventType,
            Long actorUserId,
            String actorRole,
            String source,
            String payload
    ) {
        ManagerReportReviewEvent event = new ManagerReportReviewEvent();
        event.setReview(review);
        event.setEventType(eventType);
        event.setActorUserId(actorUserId);
        event.setActorRole(actorRole);
        event.setSource(limit(clean(source), 32));
        event.setPayloadText(limit(clean(payload), 12_000));
        eventRepository.save(event);
    }

    private boolean canAccess(
            ManagerReportReviewSession review,
            User actor,
            Long telegramUserId,
            Long callbackChatId
    ) {
        return review != null && actor != null && actor.getId() != null
                && review.getManagerUserId() != null
                && review.getManagerUserId().equals(actor.getId())
                && actor.getTelegramChatId() != null
                && actor.getTelegramChatId().equals(telegramUserId)
                && review.getRecipientChatId() != null
                && review.getRecipientChatId().equals(callbackChatId);
    }

    private boolean ownerDecision(String action) {
        return OWNER_MANAGER_RIGHT.equals(action)
                || OWNER_REPORT_RIGHT.equals(action)
                || OWNER_NEEDS_CONTEXT.equals(action);
    }

    private boolean canOwnerResolve(
            ManagerReportReviewSession review,
            User actor,
            Long telegramUserId,
            Long callbackChatId
    ) {
        if (review == null || actor == null || actor.getId() == null
                || actor.getTelegramChatId() == null
                || !actor.getTelegramChatId().equals(telegramUserId)
                || !hasOwnerRole(actor)) {
            return false;
        }
        return review.getRecipientChatId() != null
                && (review.getRecipientChatId().equals(callbackChatId)
                || actor.getTelegramChatId().equals(callbackChatId));
    }

    private boolean hasOwnerRole(User actor) {
        return actor.getRoles() != null && actor.getRoles().stream()
                .anyMatch(role -> role != null
                        && ("ROLE_OWNER".equalsIgnoreCase(role.getName())
                        || "ROLE_ADMIN".equalsIgnoreCase(role.getName())));
    }

    private Long recipientChatId(Manager manager) {
        boolean groupDelivery = appSettingService.getBoolean(
                "manager.summary.manager-groups-enabled",
                true
        );
        Long groupChatId = manager == null ? null : manager.getAuditTelegramGroupChatId();
        if (groupDelivery && isGroupChat(groupChatId)) {
            return groupChatId;
        }
        return manager == null || manager.getUser() == null
                ? null
                : manager.getUser().getTelegramChatId();
    }

    private void warnAboutMissingAuditGroup(Manager manager, Long recipientChatId) {
        if (!appSettingService.getBoolean("manager.summary.manager-groups-enabled", true)
                || isGroupChat(recipientChatId)
                || manager == null
                || manager.getUser() == null
                || manager.getUser().getTelegramChatId() == null) {
            return;
        }
        log.warn("Manager audit group is not linked, private fallback used managerId={}", manager.getId());
        telegramService.sendMessage(
                manager.getUser().getTelegramChatId(),
                "⚠️ <b>Служебная группа аудита ещё не привязана.</b>\n\n"
                        + "Добавьте бота в группу менеджера и отправьте там команду <code>/auditgroup</code>. "
                        + "До привязки отчёт доставлен лично, чтобы проверка не потерялась.",
                "HTML"
        );
    }

    private String deliverySource(Long chatId) {
        return isGroupChat(chatId) ? "telegram-group" : "telegram-private";
    }

    private boolean isAuditGroupCommand(String messageText) {
        String value = clean(messageText);
        if (value.isBlank()) return false;
        String command = value.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return "/auditgroup".equals(command) || command.startsWith("/auditgroup@");
    }

    private boolean isGroupChat(Long chatId) {
        return chatId != null && chatId < 0;
    }

    private CallbackCommand parse(String data) {
        String[] parts = data.split(":");
        if (parts.length != 3) return null;
        try {
            return new CallbackCommand(parts[1], Long.parseLong(parts[2]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long parseIssueId(String action) {
        try {
            return Long.parseLong(action.substring(DISPUTE_ISSUE_PREFIX.length()));
        } catch (Exception exception) {
            return null;
        }
    }

    private int minimumReadSeconds(String report, boolean testMode) {
        if (testMode) {
            return Math.max(3, Math.min(30, appSettingService.getInt(
                    "manager.report-review.test-minimum-read-seconds",
                    10
            )));
        }
        int configured = Math.max(30, Math.min(300, appSettingService.getInt(
                "manager.report-review.minimum-read-seconds",
                60
        )));
        int byLength = Math.max(30, clean(report).replaceAll("<[^>]+>", "").length() / 24);
        return Math.min(300, Math.max(configured, byLength));
    }

    private long readSeconds(ManagerReportReviewSession review, LocalDateTime now) {
        return review.getStartedAt() == null
                ? 0
                : Math.max(0, Duration.between(review.getStartedAt(), now).toSeconds());
    }

    private String initialFallbackMessage(ManagerReportReviewSession review) {
        return (review != null && review.isTestMode()
                ? "🧪 <b>ТЕСТОВЫЙ АУДИТ</b>\n"
                + "Без блокировки доступа и влияния на показатели менеджера.\n\n"
                : "📘 <b>Персональный отчёт</b>\n\n")
                + "👤 <b>" + escape(review == null ? "Менеджер" : review.getManagerName()) + "</b>\n"
                + "Обнаружено вопросов для проверки: <b>"
                + (review == null ? 0 : review.getIssueCount()) + "</b>.\n\n"
                + "Текст отчёта станет доступен только после нажатия кнопки "
                + "<b>«Изучить отчёт»</b>.";
    }

    private String expandedFallbackMessage(ManagerReportReviewSession review) {
        String prefix = review != null && review.isTestMode()
                ? "🧪 <b>ТЕСТОВЫЙ АУДИТ</b>\n"
                + "Без блокировки доступа и влияния на показатели менеджера.\n\n"
                : "";
        String plain = clean(review == null ? null : review.getReportSnapshot())
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        plain = limit(plain, 3300);
        return prefix + "📘 <b>Персональный разбор дня</b>\n\n"
                + escape(plain)
                + "\n\n<b>Следующий шаг:</b> внимательно изучите отчёт, затем нажмите "
                + "«Подтвердить прочтение».";
    }

    private long testRunId() {
        long value = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    private String actorRole(ManagerReportReviewSession review) {
        return review != null && review.isTestMode() ? "ADMIN_TEST" : "MANAGER";
    }

    private String displayName(User user, String fallback) {
        if (user != null && user.getFio() != null && !user.getFio().isBlank()) return user.getFio();
        if (fallback != null && !fallback.isBlank()) return fallback;
        return user == null ? "Менеджер" : user.getUsername();
    }

    private static InlineKeyboardButton button(String text, String action, Long reviewId) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(CALLBACK_PREFIX + action + ":" + reviewId);
        return button;
    }

    private static String buttonTitle(ManagerReportReviewIssue issue) {
        String text = issue == null || issue.getTitle() == null
                ? "Выбрать замечание"
                : issue.getTitle().replace('\n', ' ').trim();
        return text.length() <= 52 ? text : text.substring(0, 51) + "…";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int max) {
        String text = clean(value);
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String escape(String value) {
        return clean(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record CallbackCommand(String action, Long reviewId) {
    }
}
