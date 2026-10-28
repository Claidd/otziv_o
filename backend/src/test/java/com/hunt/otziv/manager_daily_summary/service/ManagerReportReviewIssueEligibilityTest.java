package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.hunt.otziv.manager_daily_summary.model.*;
import com.hunt.otziv.manager_daily_summary.repository.*;
import com.hunt.otziv.u_users.model.Manager;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagerReportReviewIssueEligibilityTest {
    @Test
    void systemReceiptQuestionIsWithdrawnWithoutInventingAnAnswerOrClosingItsSource() {
        var fixture = new Fixture(List.of(42L));
        when(fixture.context.systemOwnsSource(42L, 9L)).thenReturn(true);

        assertThat(fixture.service.withdrawResolvedSourceIssues(fixture.review, fixture.questions)).isEqualTo(1);
        assertThat(fixture.issue.getStatus()).isEqualTo(ManagerReportReviewIssueStatus.WITHDRAWN);
        assertThat(fixture.issue.getAnsweredAt()).isNull();
        assertThat(fixture.review.getAnswersJson()).isEqualTo("[\"saved answer\"]");
        assertThat(fixture.review.getCompletedAt()).isNull();
        verify(fixture.repository).saveAll(List.of(fixture.issue));
    }

    @Test
    void aMixedQuestionRemainsUntilItsManagerSourceIsActuallyResolved() {
        var fixture = new Fixture(List.of(42L, 55L));
        when(fixture.context.systemOwnsSource(42L, 9L)).thenReturn(true);
        assertThat(fixture.service.withdrawResolvedSourceIssues(fixture.review, fixture.questions)).isZero();
        assertThat(fixture.issue.getStatus()).isEqualTo(ManagerReportReviewIssueStatus.PENDING);
        when(fixture.context.resolvedSatisfactorily(55L)).thenReturn(true);
        assertThat(fixture.service.withdrawResolvedSourceIssues(fixture.review, fixture.questions)).isEqualTo(1);
    }

    private static class Fixture {
        final ManagerReportReviewIssueRepository repository = mock(ManagerReportReviewIssueRepository.class);
        final ManagerReportReviewTaskContextService context = mock(ManagerReportReviewTaskContextService.class);
        final ManagerReportReviewSession review = new ManagerReportReviewSession();
        final ManagerReportReviewIssue issue = new ManagerReportReviewIssue();
        final List<ManagerReportReviewQualityService.ReviewQuestion> questions;
        final ManagerReportReviewIssueService service = new ManagerReportReviewIssueService(repository,
                mock(ManagerReportReviewDisputeRepository.class), mock(ManagerReportReviewQualityService.class), context);
        Fixture(List<Long> sources) {
            review.setId(41L);
            review.setManager(Manager.builder().id(9L).build());
            review.setAnswersJson("[\"saved answer\"]");
            issue.setReview(review);
            issue.setQuestionIndex(0);
            issue.setStatus(ManagerReportReviewIssueStatus.PENDING);
            questions = List.of(new ManagerReportReviewQualityService.ReviewQuestion("Что сделаете?", List.of("действие"), sources));
            when(repository.findByReview_IdOrderByQuestionIndexAsc(41L)).thenReturn(List.of(issue));
        }
    }
}
