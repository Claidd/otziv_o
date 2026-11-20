package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.model.*;
import com.hunt.otziv.manager_daily_summary.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerReportReviewSourceResolutionTest {
    private final ManagerReportReviewIssueRepository issues = mock(ManagerReportReviewIssueRepository.class);
    private final ManagerReportReviewDisputeRepository disputes = mock(ManagerReportReviewDisputeRepository.class);
    private final ManagerReportReviewTaskContextService context = mock(ManagerReportReviewTaskContextService.class);
    private final ManagerReportReviewQualityService quality = mock(ManagerReportReviewQualityService.class);
    private final ManagerReportReviewIssueService service = new ManagerReportReviewIssueService(issues,disputes,quality,context);
    private final ManagerReportReviewSession review = new ManagerReportReviewSession();
    private final ManagerReportReviewIssue issue = new ManagerReportReviewIssue();
    private final ManagerReportReviewDispute dispute = new ManagerReportReviewDispute();
    private final List<ManagerReportReviewQualityService.ReviewQuestion> questions = List.of(
            new ManagerReportReviewQualityService.ReviewQuestion("Асахи",List.of("причина"),List.of(10297L)));

    private void fixture(boolean system) {
        review.setId(214L); review.setStatus(ManagerReportReviewStatus.DISPUTED);
        issue.setId(1L); issue.setReview(review); issue.setQuestionIndex(0);
        issue.setStatus(system?ManagerReportReviewIssueStatus.DISPUTE_PENDING:ManagerReportReviewIssueStatus.DISPUTED);
        dispute.setIssue(issue); dispute.setVerificationRequestedBySystem(system);
        dispute.setStatus(system?ManagerReportReviewDisputeStatus.DRAFT:ManagerReportReviewDisputeStatus.OPEN);
        dispute.setManagerText(system?null:"Задачи закончены, счёт в бане");
        when(issues.findByReview_IdOrderByQuestionIndexAsc(214L)).thenReturn(List.of(issue));
        when(disputes.findByIssue_Review_IdOrderByCreatedAtAsc(214L)).thenReturn(List.of(dispute));
        when(disputes.findFirstByIssue_Review_IdAndStatusInOrderByCreatedAtDesc(eq(214L),any())).thenReturn(Optional.of(dispute));
    }

    @Test void resolvedSourceWithdrawsObsoleteQuestionAndPreservesTheHumanDisputeAsHistory() {
        fixture(false); when(context.resolvedSatisfactorily(10297L)).thenReturn(true);
        assertThat(service.withdrawResolvedSourceIssues(review,questions)).isEqualTo(1);
        assertThat(issue.getStatus()).isEqualTo(ManagerReportReviewIssueStatus.WITHDRAWN);
        assertThat(dispute.getStatus()).isEqualTo(ManagerReportReviewDisputeStatus.SUPERSEDED);
        assertThat(dispute.getResolvedByUserId()).isNull();
        assertThat(dispute.getManagerText()).isEqualTo("Задачи закончены, счёт в бане");
        assertThat(dispute.getOwnerComment()).contains("не решение владельца");
        assertThat(service.withdrawResolvedSourceIssues(review,questions)).isZero();
        verify(disputes,times(1)).save(dispute);
    }

    @Test void unresolvedSourceAndHumanDisputesAreNotAutomaticallyAccepted() {
        fixture(false);
        assertThat(service.withdrawResolvedSourceIssues(review,questions)).isZero();
        assertThat(service.reopenSystemVerification(review,0)).isFalse();
        assertThat(dispute.getStatus()).isEqualTo(ManagerReportReviewDisputeStatus.OPEN);
        verify(disputes,never()).save(any());
    }

    @Test void newEvidenceCanReopenOnlyUntouchedSystemVerificationWithoutAcceptingTheAnswer() {
        fixture(true);
        assertThat(service.reopenSystemVerification(review,0)).isTrue();
        assertThat(issue.getStatus()).isEqualTo(ManagerReportReviewIssueStatus.PENDING);
        assertThat(dispute.getStatus()).isEqualTo(ManagerReportReviewDisputeStatus.SUPERSEDED);
        assertThat(dispute.getOwnerComment()).contains("ещё не признан верным");
        assertThat(service.reopenSystemVerification(review,0)).isFalse();
    }
}
