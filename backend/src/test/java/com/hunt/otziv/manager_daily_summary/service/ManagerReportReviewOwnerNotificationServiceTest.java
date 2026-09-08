package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.model.*;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagerReportReviewOwnerNotificationServiceTest {
    private final AppSettingService settings = mock(AppSettingService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TelegramService telegram = mock(TelegramService.class);
    private final ManagerReportReviewOwnerNotificationService service =
            new ManagerReportReviewOwnerNotificationService(settings, users, telegram);

    @Test
    void groupDisputeAlsoReachesOwnerAndClearlyLabelsMissingExplanation() {
        ManagerReportReviewSession review = review();
        ManagerReportReviewDispute dispute = dispute(review);
        when(settings.getString("manager.summary.recipients", "ADMIN,OWNER")).thenReturn("OWNER");
        when(users.findAllOwners("ROLE_OWNER")).thenReturn(List.of(
                User.builder().id(1L).active(true).telegramChatId(800L).build()));
        when(telegram.sendMessageWithInlineKeyboard(anyLong(), any(), any(), any())).thenReturn(true);

        var result = service.notifyDispute(review, dispute);

        assertThat(result.delivered()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        verify(telegram).sendMessageWithInlineKeyboard(eq(800L),
                argThat(text -> text.contains("Ещё не отправлено") && text.contains("No problem")), eq("HTML"),
                argThat(rows -> rows.stream().allMatch(row ->
                        row.getFirst().getCallbackData().endsWith(":45:5"))));
        verify(telegram).sendMessageWithInlineKeyboard(eq(-100900L), any(), eq("HTML"),
                argThat(rows -> rows.getLast().getFirst().getCallbackData().equals("manager-review:dispute:45")));
    }

    @Test
    void testAuditDoesNotNotifyRealOwnersAndDeliveryFailureIsVisible() {
        ManagerReportReviewSession review = review();
        review.setTestMode(true);

        var result = service.notifyDispute(review, dispute(review));

        assertThat(result.delivered()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        verifyNoInteractions(users);
        verify(telegram).sendMessageWithInlineKeyboard(eq(-100900L), any(), any(), any());
    }

    private ManagerReportReviewSession review() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(45L);
        review.setManagerName("Вика");
        review.setSummaryDate(LocalDate.of(2026, 8, 26));
        review.setRecipientChatId(-100900L);
        return review;
    }

    private ManagerReportReviewDispute dispute(ManagerReportReviewSession review) {
        ManagerReportReviewIssue issue = new ManagerReportReviewIssue();
        issue.setId(99L);
        issue.setReview(review);
        issue.setQuestionText("No problem: почему не ответили клиенту?");
        ManagerReportReviewDispute dispute = new ManagerReportReviewDispute();
        dispute.setId(5L);
        dispute.setIssue(issue);
        dispute.setStatus(ManagerReportReviewDisputeStatus.DRAFT);
        return dispute;
    }
}
