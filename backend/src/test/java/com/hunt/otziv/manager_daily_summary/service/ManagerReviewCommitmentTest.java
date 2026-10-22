package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReviewCommitment;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReviewCommitmentRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ManagerReviewCommitmentTest {
    private final ManagerReviewCommitmentRepository promises = mock(ManagerReviewCommitmentRepository.class);
    private final ManagerDailyControlConcreteItemRepository sources = mock(ManagerDailyControlConcreteItemRepository.class);
    private final ManagerReportReviewCommitmentService service = new ManagerReportReviewCommitmentService(promises, sources);

    @Test void promiseKeepsCaseOpenAndPreservesEarlierIndependentAction() {
        var source = source(9L);
        var prior = new ManagerReviewCommitment(); prior.setDueAt(LocalDateTime.of(2026,10,23,10,0)); prior.setStatus("PLANNED");
        when(promises.findBySourceTaskIdAndStatusIn(eq(123L), any())).thenReturn(List.of(prior));
        when(sources.findByIdForUpdate(123L)).thenReturn(Optional.of(source));
        service.record(review(false), 0, question(), List.of(promise()));
        var captured = ArgumentCaptor.forClass(ManagerReviewCommitment.class);
        verify(promises).save(captured.capture());
        assertThat(captured.getValue().getDueAt()).isEqualTo(LocalDateTime.of(2026,10,23,20,0));
        assertThat(source.getStatus()).isEqualTo(ManagerDailyControlItemStatus.DEFERRED);
        assertThat(source.getResolvedAt()).isNull();
        assertThat(source.getFollowUpAt()).isEqualTo(prior.getDueAt());
        assertThat(prior.getStatus()).isEqualTo("PLANNED");
    }

    @Test void testAuditCannotCreateWorkingPromise() {
        service.record(review(true), 0, question(), List.of(promise()));
        verifyNoInteractions(promises, sources);
    }

    @Test void sourceOwnedByAnotherManagerCannotReceivePromise() {
        var foreignSource = source(17L);
        when(sources.findByIdForUpdate(123L)).thenReturn(Optional.of(foreignSource));
        assertThatThrownBy(() -> service.record(review(false),0,question(),List.of(promise())))
                .isInstanceOf(IllegalArgumentException.class);
        verify(promises,never()).save(any());
    }

    @Test void completingCaseDoesNotProveWhoPerformedPromisedAction() {
        var context = mock(ManagerReportReviewTaskContextService.class);
        var promise = new ManagerReviewCommitment(); promise.setSourceTaskId(123L); promise.setDueAt(LocalDateTime.now().minusHours(1));
        when(promises.findByStatusInOrderByCheckedAtAsc(any(),any())).thenReturn(List.of(promise));
        when(context.resolvedSatisfactorily(123L)).thenReturn(true);
        new ManagerReviewCommitmentJob(promises,context).verifyResults();
        assertThat(promise.getStatus()).isEqualTo("CASE_RESOLVED");
        assertThat(promise.getCheckedAt()).isNotNull();
    }

    @Test void overdueUnverifiedPromiseRemainsDue() {
        var context = mock(ManagerReportReviewTaskContextService.class);
        var promise = new ManagerReviewCommitment(); promise.setSourceTaskId(123L); promise.setDueAt(LocalDateTime.now().minusHours(1));
        when(promises.findByStatusInOrderByCheckedAtAsc(any(),any())).thenReturn(List.of(promise));
        new ManagerReviewCommitmentJob(promises,context).verifyResults();
        assertThat(promise.getStatus()).isEqualTo("OVERDUE");
    }

    @Test void showsPromisesAcrossInvoiceCardTypesButNeverAcrossManagers() {
        var current = source(9L); current.setEntityType("COMMON_INVOICE"); current.setEntityId(189L);
        var automation = source(9L); automation.setId(124L); automation.setEntityType("COMMON_INVOICE_AUTOMATION");
        var foreign = source(17L); foreign.setId(125L);
        when(sources.findById(123L)).thenReturn(Optional.of(current));
        when(sources.findByEntityTypeStartingWithAndEntityId("COMMON_INVOICE", 189L))
                .thenReturn(List.of(current, automation, foreign));
        service.forSource(123L);
        verify(promises).findTop20BySourceTaskIdInOrderByCreatedAtDesc(List.of(123L, 124L));
    }

    private ManagerDailyControlConcreteItem source(long managerId) {
        var source=new ManagerDailyControlConcreteItem(); source.setId(123L); source.setStatus(ManagerDailyControlItemStatus.DEFERRED);
        var control=mock(ManagerDailyControl.class); when(control.managerId()).thenReturn(managerId); source.setControl(control); return source;
    }
    private ManagerReportReviewSession review(boolean test) {
        var review=new ManagerReportReviewSession(); review.setId(41L); review.setManager(Manager.builder().id(9L).build()); review.setTestMode(test); return review;
    }
    private ManagerReportReviewQualityService.ReviewQuestion question() {
        return new ManagerReportReviewQualityService.ReviewQuestion("Что сделаете и когда проверите?",List.of("действие","срок"),List.of(123L));
    }
    private ManagerReportReviewQualityService.ActionCommitment promise() {
        return new ManagerReportReviewQualityService.ActionCommitment(123L,"Проверю журнал доставки","Менеджер",
                "2026-10-23T12:00:00Z","Найду подтверждение операции",List.of("Доступ администратора"));
    }
}
