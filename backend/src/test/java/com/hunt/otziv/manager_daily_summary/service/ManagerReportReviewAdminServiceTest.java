package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDispute;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDisputeStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewIssueRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewDisputeRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagerReportReviewAdminServiceTest {

    private ManagerReportReviewSessionRepository sessionRepository;
    private ManagerReportReviewEventRepository eventRepository;
    private ManagerReportReviewAccessPolicy accessPolicy;
    private TelegramService telegramService;
    private ManagerReportReviewIssueService issueService;
    private ManagerReportReviewIssueRepository issueRepository;
    private ManagerReportReviewDisputeRepository disputeRepository;
    private ManagerReportReviewAdminService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ManagerReportReviewSessionRepository.class);
        eventRepository = mock(ManagerReportReviewEventRepository.class);
        accessPolicy = mock(ManagerReportReviewAccessPolicy.class);
        telegramService = mock(TelegramService.class);
        issueService = mock(ManagerReportReviewIssueService.class);
        issueRepository = mock(ManagerReportReviewIssueRepository.class);
        disputeRepository = mock(ManagerReportReviewDisputeRepository.class);
        service = new ManagerReportReviewAdminService(
                sessionRepository,
                eventRepository,
                accessPolicy,
                telegramService,
                issueService,
                issueRepository,
                disputeRepository
        );
    }

    @Test
    void rejectingDisputeAfterCompletedReviewDoesNotReopenLastQuestion() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 7, 26, 1, 53);
        ManagerReportReviewSession review = review();
        review.setStatus(ManagerReportReviewStatus.DISPUTED);
        review.setIssueCount(3);
        review.setCurrentQuestionIndex(3);
        review.setReadingConfirmedAt(completedAt.minusMinutes(45));
        review.setCompletedAt(completedAt);
        review.setRestrictedAt(completedAt.minusMinutes(5));
        review.setRestrictionReleasedAt(completedAt);
        when(sessionRepository.findById(2L)).thenReturn(Optional.of(review));

        service.resolveDispute(
                2L,
                ManagerReportReviewAdminService.REPORT_CONFIRMED,
                "Исходный отчёт подтверждён",
                User.builder().id(1L).username("owner").active(true).build()
        );

        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.COMPLETED);
        assertThat(review.getCompletedAt()).isEqualTo(completedAt);
        assertThat(review.getCurrentQuestionIndex()).isEqualTo(3);
        assertThat(review.isAuditRequired()).isFalse();
        verify(accessPolicy).invalidate(13L);
        verify(telegramService).sendMessage(
                eq(-100500L),
                contains("отвечать повторно не нужно"),
                eq("HTML")
        );
    }

    @Test
    void rejectingDisputeDuringReviewContinuesFromRemainingQuestion() {
        ManagerReportReviewSession review = review();
        review.setStatus(ManagerReportReviewStatus.DISPUTED);
        review.setIssueCount(3);
        review.setCurrentQuestionIndex(1);
        review.setReadingConfirmedAt(LocalDateTime.now().minusMinutes(20));
        when(sessionRepository.findById(2L)).thenReturn(Optional.of(review));

        service.resolveDispute(
                2L,
                ManagerReportReviewAdminService.REPORT_CONFIRMED,
                "",
                User.builder().id(1L).username("owner").active(true).build()
        );

        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.QUESTION_PENDING);
        assertThat(review.getCompletedAt()).isNull();
        assertThat(review.getCurrentQuestionIndex()).isEqualTo(1);
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(-100500L),
                contains("оставшиеся вопросы"),
                eq("HTML"),
                eq(ManagerReportReviewTelegramService.continueKeyboard(2L))
        );
    }

    @Test
    void acceptingOneIssueBeforeReadingConfirmationDoesNotCompleteWholeAudit() {
        ManagerReportReviewSession review = review();
        review.setStatus(ManagerReportReviewStatus.READING);
        review.setIssueCount(2);
        ManagerReportReviewIssue disputedIssue = issue(review, 0, "Неверный процент 18%");
        disputedIssue.setStatus(ManagerReportReviewIssueStatus.DISPUTED);
        ManagerReportReviewIssue remainingIssue = issue(review, 1, "Формальный ответ клиенту");
        ManagerReportReviewDispute dispute = new ManagerReportReviewDispute();
        dispute.setId(20L);
        dispute.setIssue(disputedIssue);
        dispute.setStatus(ManagerReportReviewDisputeStatus.OPEN);
        dispute.setPreviousIssueStatus(ManagerReportReviewIssueStatus.PENDING);
        dispute.setPreviousSessionStatus(ManagerReportReviewStatus.READING);
        when(sessionRepository.findById(2L)).thenReturn(Optional.of(review));
        when(issueService.openDispute(review)).thenReturn(Optional.of(dispute));
        when(issueService.hasUnresolvedDisputes(review)).thenReturn(false);
        when(issueService.validIssueCount(review)).thenReturn(1L);
        when(issueService.answeredCount(review)).thenReturn(0L);
        when(issueService.hasPendingQuestions(review)).thenReturn(true);
        when(issueService.nextPending(review, 0)).thenReturn(Optional.of(remainingIssue));

        service.resolveDispute(
                2L,
                ManagerReportReviewAdminService.REPORT_INCORRECT,
                "Процент рассчитан без автоматических закрытий",
                User.builder().id(1L).username("owner").active(true).build()
        );

        assertThat(disputedIssue.getStatus()).isEqualTo(ManagerReportReviewIssueStatus.WITHDRAWN);
        assertThat(dispute.getStatus()).isEqualTo(ManagerReportReviewDisputeStatus.ACCEPTED);
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.READING);
        assertThat(review.getCompletedAt()).isNull();
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(-100500L),
                contains("Прочтение ещё не подтверждено"),
                eq("HTML"),
                eq(ManagerReportReviewTelegramService.continueKeyboard(2L))
        );
    }

    private ManagerReportReviewSession review() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(2L);
        review.setManagerUserId(13L);
        review.setRecipientChatId(-100500L);
        review.setAuditRequired(true);
        return review;
    }

    private ManagerReportReviewIssue issue(
            ManagerReportReviewSession review,
            int index,
            String title
    ) {
        ManagerReportReviewIssue issue = new ManagerReportReviewIssue();
        issue.setId(10L + index);
        issue.setReview(review);
        issue.setQuestionIndex(index);
        issue.setTitle(title);
        issue.setQuestionText(title);
        issue.setStatus(ManagerReportReviewIssueStatus.PENDING);
        return issue;
    }
}
