package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDispute;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDisputeStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewDisputeRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewIssueRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewAdminService {

    public static final String REPORT_INCORRECT = "REPORT_INCORRECT";
    public static final String REPORT_CONFIRMED = "REPORT_CONFIRMED";
    public static final String REPORT_NEEDS_CONTEXT = "REPORT_NEEDS_CONTEXT";

    private final ManagerReportReviewSessionRepository sessionRepository;
    private final ManagerReportReviewEventRepository eventRepository;
    private final ManagerReportReviewAccessPolicy accessPolicy;
    private final TelegramService telegramService;
    private final ManagerReportReviewIssueService issueService;
    private final ManagerReportReviewIssueRepository issueRepository;
    private final ManagerReportReviewDisputeRepository disputeRepository;

    @Transactional
    public void resolveDispute(Long reviewId, String action, String comment, User actor) {
        ManagerReportReviewSession review = sessionRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Разбор не найден"));
        var issueDispute = issueService.openDispute(review);
        if (issueDispute.isPresent()) {
            resolveIssueDispute(
                    review,
                    issueDispute.get(),
                    action,
                    comment,
                    actor
            );
            return;
        }
        if (review.getStatus() != ManagerReportReviewStatus.DISPUTED
                && review.getStatus() != ManagerReportReviewStatus.DISPUTE_PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У этого разбора нет открытого спора");
        }
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        if (REPORT_INCORRECT.equals(normalizedAction)) {
            review.setStatus(ManagerReportReviewStatus.COMPLETED);
            review.setAnswerQuality("REPORT_WITHDRAWN");
            review.setAnswerQualityReason(limit(
                    "Владелец подтвердил фактическую ошибку отчёта. " + clean(comment),
                    1000
            ));
            review.setAuditRequired(false);
            review.setCompletedAt(now);
            review.setRestrictionReleasedAt(now);
            sessionRepository.save(review);
            event(review, actor, "DISPUTE_ACCEPTED", comment);
            if (review.getRestrictedAt() != null) {
                event(review, actor, "RESTRICTION_RELEASED",
                        "Ошибка отчёта подтверждена владельцем");
            }
            accessPolicy.invalidate(review.getManagerUserId());
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "✅ <b>Спор принят</b>\n\nВладелец подтвердил неточность отчёта. "
                            + "Разбор закрыт, доступ ко всем разделам восстановлен.",
                    "HTML"
            );
            return;
        }
        if (REPORT_CONFIRMED.equals(normalizedAction)) {
            boolean reviewWasCompleted = review.getCompletedAt() != null
                    || (review.getReadingConfirmedAt() != null
                    && review.getIssueCount() > 0
                    && review.getCurrentQuestionIndex() >= review.getIssueCount());
            if (reviewWasCompleted) {
                review.setStatus(ManagerReportReviewStatus.COMPLETED);
                review.setCompletedAt(review.getCompletedAt() == null ? now : review.getCompletedAt());
                review.setRestrictionReleasedAt(now);
                review.setAuditRequired(false);
                review.setAnswerQuality("DISPUTE_REJECTED");
                review.setAnswerQualityReason(limit(
                        "Владелец проверил спор и подтвердил исходный отчёт. "
                                + "Ранее пройденная проверка сохранена. " + clean(comment),
                        1000
                ));
                sessionRepository.save(review);
                event(review, actor, "DISPUTE_REJECTED_AFTER_COMPLETION", comment);
                if (review.getRestrictedAt() != null) {
                    event(review, actor, "RESTRICTION_RELEASED",
                            "Повторная проверка не требуется: все вопросы уже были пройдены");
                }
                accessPolicy.invalidate(review.getManagerUserId());
                telegramService.sendMessage(
                        review.getRecipientChatId(),
                        "⚖️ <b>Отчёт проверен владельцем</b>\n\nИсходный вывод подтверждён. "
                                + "Все вопросы вы уже прошли, поэтому отвечать повторно не нужно. "
                                + "Проверка завершена, доступ ко всем разделам сохранён.",
                        "HTML"
                );
                return;
            }
            review.setStatus(review.getReadingConfirmedAt() == null
                    ? ManagerReportReviewStatus.READING
                    : ManagerReportReviewStatus.QUESTION_PENDING);
            review.setCompletedAt(null);
            review.setDeadlineStartedAt(now);
            review.setReminderOneSentAt(null);
            review.setReminderThreeSentAt(null);
            review.setRestrictedAt(null);
            review.setRestrictionReleasedAt(null);
            review.setAuditRequired(false);
            review.setAnswerQuality("DISPUTE_REJECTED");
            review.setAnswerQualityReason(limit(
                    "Владелец проверил спор и подтвердил исходный отчёт. " + clean(comment),
                    1000
            ));
            sessionRepository.save(review);
            event(review, actor, "DISPUTE_REJECTED", comment);
            accessPolicy.invalidate(review.getManagerUserId());
            telegramService.sendMessageWithInlineKeyboard(
                    review.getRecipientChatId(),
                    "⚖️ <b>Отчёт проверен владельцем</b>\n\nИсходный вывод подтверждён. "
                            + "Продолжите проверку и ответьте на оставшиеся вопросы, чтобы восстановить доступ.",
                    "HTML",
                    ManagerReportReviewTelegramService.continueKeyboard(review.getId())
            );
            return;
        }
        if (REPORT_NEEDS_CONTEXT.equals(normalizedAction)) {
            review.setStatus(ManagerReportReviewStatus.DISPUTED);
            review.setAuditRequired(true);
            review.setAnswerQuality("OWNER_NEEDS_CONTEXT");
            review.setAnswerQualityReason(limit(
                    "Владелец запросил дополнительный контекст. " + clean(comment),
                    1000
            ));
            sessionRepository.save(review);
            event(review, actor, "DISPUTE_CONTEXT_REQUESTED", comment);
            accessPolicy.invalidate(review.getManagerUserId());
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "🔎 <b>Для решения не хватает контекста</b>\n\n"
                            + "Замечание пока не считается подтверждённой ошибкой менеджера и "
                            + "не ограничивает доступ. Владелец продолжит проверку по полной переписке.",
                    "HTML"
            );
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестное решение по спору");
    }

    private void resolveIssueDispute(
            ManagerReportReviewSession review,
            ManagerReportReviewDispute dispute,
            String action,
            String comment,
            User actor
    ) {
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        ManagerReportReviewIssue issue = dispute.getIssue();
        dispute.setOwnerComment(limit(clean(comment), 2000));
        dispute.setResolvedAt(now);
        dispute.setResolvedByUserId(actor == null ? null : actor.getId());

        if (REPORT_INCORRECT.equals(normalizedAction)) {
            issue.setStatus(ManagerReportReviewIssueStatus.WITHDRAWN);
            dispute.setStatus(ManagerReportReviewDisputeStatus.ACCEPTED);
            saveIssueDecision(issue, dispute);
            review.setAuditRequired(issueService.hasUnresolvedDisputes(review));
            event(review, actor, "ISSUE_DISPUTE_ACCEPTED",
                    issue.getTitle() + "\n" + clean(comment));
            continueAfterIssueDecision(review, now, true);
            return;
        }

        if (REPORT_CONFIRMED.equals(normalizedAction)) {
            issue.setStatus(dispute.getPreviousIssueStatus() == ManagerReportReviewIssueStatus.ANSWERED
                    ? ManagerReportReviewIssueStatus.ANSWERED
                    : ManagerReportReviewIssueStatus.PENDING);
            dispute.setStatus(ManagerReportReviewDisputeStatus.REJECTED);
            saveIssueDecision(issue, dispute);
            review.setAuditRequired(issueService.hasUnresolvedDisputes(review));
            event(review, actor, "ISSUE_DISPUTE_REJECTED",
                    issue.getTitle() + "\n" + clean(comment));
            continueAfterIssueDecision(review, now, false);
            return;
        }

        if (REPORT_NEEDS_CONTEXT.equals(normalizedAction)) {
            issue.setStatus(ManagerReportReviewIssueStatus.NEEDS_CONTEXT);
            dispute.setStatus(ManagerReportReviewDisputeStatus.NEEDS_CONTEXT);
            saveIssueDecision(issue, dispute);
            review.setAuditRequired(true);
            if (review.getReadingConfirmedAt() == null) {
                review.setStatus(ManagerReportReviewStatus.READING);
            } else if (issueService.hasPendingQuestions(review)) {
                review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
            } else {
                review.setStatus(ManagerReportReviewStatus.DISPUTED);
            }
            review.setCompletedAt(null);
            sessionRepository.save(review);
            event(review, actor, "ISSUE_DISPUTE_CONTEXT_REQUESTED",
                    issue.getTitle() + "\n" + clean(comment));
            accessPolicy.invalidate(review.getManagerUserId());
            telegramService.sendMessageWithInlineKeyboard(
                    review.getRecipientChatId(),
                    "🔎 <b>По выбранному замечанию не хватает данных</b>\n\n"
                            + "Оно не считается нарушением до дополнительной проверки. "
                            + (issueService.hasPendingQuestions(review)
                            ? "Остальные вопросы аудита необходимо продолжить."
                            : "Остальных вопросов сейчас нет."),
                    "HTML",
                    issueService.hasPendingQuestions(review)
                            ? ManagerReportReviewTelegramService.continueKeyboard(review.getId())
                            : java.util.List.of()
            );
            return;
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестное решение по спору");
    }

    private void saveIssueDecision(
            ManagerReportReviewIssue issue,
            ManagerReportReviewDispute dispute
    ) {
        issueRepository.save(issue);
        disputeRepository.save(dispute);
    }

    private void continueAfterIssueDecision(
            ManagerReportReviewSession review,
            LocalDateTime now,
            boolean managerRight
    ) {
        boolean hasPending = issueService.hasPendingQuestions(review);
        boolean hasUnresolved = issueService.hasUnresolvedDisputes(review);
        long validIssues = issueService.validIssueCount(review);
        long answered = issueService.answeredCount(review);

        if (validIssues == 0) {
            review.setStatus(ManagerReportReviewStatus.COMPLETED);
            review.setCompletedAt(now);
            review.setAuditRequired(false);
            review.setAnswerQuality("REPORT_WITHDRAWN");
            review.setAnswerQualityReason(
                    "Все замечания аудита признаны некорректными; аудит отменён, а не пройден"
            );
            review.setRestrictionReleasedAt(now);
            sessionRepository.save(review);
            event(review, null, "REVIEW_CANCELLED_NO_VALID_ISSUES",
                    "После решений владельца действующих замечаний не осталось");
            accessPolicy.invalidate(review.getManagerUserId());
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    "✅ <b>Оспоренное замечание снято</b>\n\n"
                            + "Действующих замечаний больше нет. Аудит отменён из-за ошибок отчёта; "
                            + "он не считается проверкой, пройденной менеджером.",
                    "HTML"
            );
            return;
        }

        if (review.getReadingConfirmedAt() == null) {
            review.setStatus(ManagerReportReviewStatus.READING);
            review.setCompletedAt(null);
        } else if (hasPending) {
            review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
            review.setCompletedAt(null);
            issueService.nextPending(review, 0)
                    .ifPresent(issue -> review.setCurrentQuestionIndex(issue.getQuestionIndex()));
        } else if (hasUnresolved) {
            review.setStatus(ManagerReportReviewStatus.DISPUTED);
            review.setCompletedAt(null);
        } else {
            review.setStatus(ManagerReportReviewStatus.COMPLETED);
            review.setCompletedAt(review.getCompletedAt() == null ? now : review.getCompletedAt());
            review.setAnswerQuality(managerRight ? "CORRECTED_AND_PASSED" : "ACCEPTED");
            review.setAnswerQualityReason(managerRight
                    ? "Оспоренное замечание снято; все остальные действующие вопросы пройдены"
                    : "Замечание подтверждено; все действующие вопросы уже пройдены");
            review.setRestrictionReleasedAt(now);
        }
        sessionRepository.save(review);
        accessPolicy.invalidate(review.getManagerUserId());

        if (review.getStatus() == ManagerReportReviewStatus.COMPLETED) {
            telegramService.sendMessage(
                    review.getRecipientChatId(),
                    managerRight
                            ? "✅ <b>Замечание снято</b>\n\nОстальные вопросы уже пройдены. "
                            + "Аудит скорректирован и завершён."
                            : "⚖️ <b>Замечание подтверждено</b>\n\nОтвет по нему уже был принят. "
                            + "Аудит остаётся завершённым.",
                    "HTML"
            );
            return;
        }

        String text = managerRight
                ? "✅ <b>Оспоренное замечание снято</b>\n\n"
                : "⚖️ <b>Оспоренное замечание подтверждено</b>\n\n";
        text += review.getReadingConfirmedAt() == null
                ? "Прочтение ещё не подтверждено. Продолжите аудит."
                : hasPending
                        ? "Осталось действующих вопросов: "
                        + Math.max(1, validIssues - answered) + ". Продолжите проверку."
                        : "Других вопросов нет; ожидается решение по оставшемуся спору.";
        telegramService.sendMessageWithInlineKeyboard(
                review.getRecipientChatId(),
                text,
                "HTML",
                ManagerReportReviewTelegramService.continueKeyboard(review.getId())
        );
    }

    private void event(
            ManagerReportReviewSession review,
            User actor,
            String eventType,
            String comment
    ) {
        ManagerReportReviewEvent event = new ManagerReportReviewEvent();
        event.setReview(review);
        event.setEventType(eventType);
        event.setActorUserId(actor == null ? null : actor.getId());
        event.setActorRole("OWNER");
        event.setSource("manager-control");
        event.setPayloadText(limit(clean(comment), 2000));
        eventRepository.save(event);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int max) {
        String text = clean(value);
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
