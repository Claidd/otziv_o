package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hunt.otziv.manager_daily_summary.model.*;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReviewCommitmentRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class ManagerWorkerGoalFollowupTest {
    final ManagerReviewCommitmentRepository repository=mock(ManagerReviewCommitmentRepository.class);
    final ManagerDailyControlConcreteItemRepository sources=mock(ManagerDailyControlConcreteItemRepository.class);
    final ManagerReportReviewQualityService quality=mock(ManagerReportReviewQualityService.class);
    final ManagerWorkerDailyProgressService progress=mock(ManagerWorkerDailyProgressService.class);
    final ManagerReportReviewCommitmentService service=new ManagerReportReviewCommitmentService(repository,sources,quality,progress);
    final LocalDate date=LocalDate.of(2026,11,1);
    final ManagerReportReviewQualityService.ReviewQuestion question=new ManagerReportReviewQualityService.ReviewQuestion(
            "Специалист Виктория Ц. за 01.11.2026: как поможете?",List.of("действие и проверка специалиста"));
    final ManagerReportReviewSession review=new ManagerReportReviewSession();

    @BeforeEach void setup() {
        review.setId(181L);review.setManager(Manager.builder().id(2L).build());review.setManagerName("Вика Ц.");
        when(quality.workerGoalTarget("original evidence")).thenReturn(Optional.of(
                new ManagerReportReviewQualityService.WorkerGoalTarget(13L,"Виктория Ц.",date.toString(),5)));
        when(progress.missedPreviousDayGoals(2L,date.plusDays(1))).thenReturn(List.of(
                new ManagerWorkerDailyProgressService.MissedWorkerGoal(13L,"Виктория Ц.",date,57,62)));
    }

    @Test void keepsOriginalDayVolumeAndExplicitDeadlineWithoutClosingWork() {
        service.recordWorkerGoal(review,0,question,assessment(),"original evidence");
        var rows=ArgumentCaptor.forClass(ManagerReviewCommitment.class);verify(repository).save(rows.capture());
        var row=rows.getValue();
        assertThat(row.getSourceTaskId()).isNull();assertThat(row.getWorkerId()).isEqualTo(13L);
        assertThat(row.getGoalDate()).isEqualTo(date);assertThat(row.getGoalRemaining()).isEqualTo(5);
        assertThat(row.getDueAt()).isEqualTo(LocalDateTime.of(2026,11,4,18,0));
        assertThat(row.getStatus()).isEqualTo("WORKER_PLANNED");verifyNoInteractions(sources);
        assertThat(ManagerReportReviewCommitmentService.OPEN).doesNotContain(row.getStatus());
    }

    @Test void cannotStoreAnotherManagersWorker() {
        when(progress.missedPreviousDayGoals(2L,date.plusDays(1))).thenReturn(List.of());
        assertThatThrownBy(()->service.recordWorkerGoal(review,0,question,assessment(),"original evidence"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository,never()).save(any());
    }

    @Test void testReviewCannotCreateRealFollowup() {
        review.setTestMode(true);service.recordWorkerGoal(review,0,question,assessment(),"original evidence");
        verifyNoInteractions(repository,sources);
    }

    @Test void newDailySuccessCannotResolveTheOldDebt() {
        var context=mock(ManagerReportReviewTaskContextService.class);
        var row=row();row.setDueAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findByStatusInOrderByCheckedAtAsc(any(),any())).thenReturn(List.of(row));
        new ManagerReviewCommitmentJob(repository,context).verifyResults();
        assertThat(row.getStatus()).isEqualTo("WORKER_OVERDUE");assertThat(row.getGoalDate()).isEqualTo(date);
        assertThat(row.getGoalRemaining()).isEqualTo(5);verifyNoInteractions(context);
    }

    @Test void anUnspecifiedDeadlineCreatesAResultCheckWithoutInventingOverdueWork() {
        var row=row();row.setCreatedAt(LocalDateTime.now().minusDays(2));
        when(repository.findByStatusInOrderByCheckedAtAsc(any(),any())).thenReturn(List.of(row));
        new ManagerReviewCommitmentJob(repository,mock(ManagerReportReviewTaskContextService.class)).verifyResults();
        assertThat(row.getStatus()).isEqualTo("WORKER_NEEDS_CONTEXT");assertThat(row.getDueAt()).isNull();
    }

    @Test void dueQuestionTargetsTheExactOldDateAndVolume() {
        when(repository.findDueWorkerGoals(eq(2L),any(),any(),any())).thenReturn(List.of(row()));
        var due=service.dueQuestions(2L);
        assertThat(due).hasSize(1);assertThat(due.getFirst().question())
                .contains("01.11.2026","недобору 5 задач","именно по этому оставшемуся объёму");
        assertThat(due.getFirst().sourceTaskIds()).isEmpty();
    }

    @Test void newPlanRetainsTheOldPromiseInsteadOfOverwritingItsWords() {
        var old=row();old.setAction("Исходное обещание");
        when(repository.findByManagerIdAndWorkerIdAndGoalDateAndStatusIn(eq(2L),eq(13L),eq(date),any())).thenReturn(List.of(old));
        service.recordWorkerGoal(review,0,question,assessment(),"original evidence");
        assertThat(old.getStatus()).isEqualTo("REPLANNED");assertThat(old.getAction()).isEqualTo("Исходное обещание");
        verify(repository,times(2)).save(any());
    }

    @Test void ownerWithdrawalEndsMonitoringWithoutClaimingCompletion() {
        var row=row();var issue=new ManagerReportReviewIssue();issue.setQuestionJson("saved question");
        when(quality.readQuestions("saved question")).thenReturn(List.of(question));
        when(repository.findByManagerIdAndWorkerIdAndGoalDateAndStatusIn(eq(2L),eq(13L),eq(date),any())).thenReturn(List.of(row));
        service.withdrawWorkerGoal(review,issue,"Недобор ошибочно отнесён к менеджеру");
        assertThat(row.getStatus()).isEqualTo("OWNER_WITHDRAWN");assertThat(row.getResolvedAt()).isNotNull();
        assertThat(row.getResolutionNote()).contains("аудите №181");verifyNoInteractions(sources);
    }

    private ManagerReviewCommitment row() {
        var row=new ManagerReviewCommitment();row.setManagerId(2L);row.setWorkerId(13L);row.setWorkerName("Виктория Ц.");
        row.setStatus("WORKER_PLANNED");
        row.setGoalDate(date);row.setGoalRemaining(5L);row.setAction("Проверю пять исходных текстов");
        row.setVerification("Пять текстов в исходном заказе");row.setCreatedAt(LocalDateTime.now());return row;
    }
    private ManagerReportReviewQualityService.Assessment assessment() {
        return new ManagerReportReviewQualityService.Assessment(true,100,"Принят","","deepseek",List.of(),false,"",
                ManagerReportReviewQualityService.Decision.ACCEPTED,List.of(),List.of(),List.of(
                new ManagerReportReviewQualityService.ActionCommitment(null,"Разберу недобор и помогу с приоритетами","Менеджер",
                        "2026-11-04T18:00:00+08:00","Проверю пять текстов в карточке заказа",List.of())));
    }
}
