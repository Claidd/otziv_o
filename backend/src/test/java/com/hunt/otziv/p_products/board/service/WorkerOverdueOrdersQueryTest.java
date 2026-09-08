package com.hunt.otziv.p_products.board.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.p_products.application.WorkerOrderCommandException;
import com.hunt.otziv.p_products.application.WorkerStaffAccessPolicy;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class WorkerOverdueOrdersQueryTest {
    private final WorkerStaffAccessPolicy access = mock(WorkerStaffAccessPolicy.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final WorkerBoardTaskQueries tasks = mock(WorkerBoardTaskQueries.class);
    private final WorkerOverdueOrdersQuery query = new WorkerOverdueOrdersQuery(access, orders, tasks);
    private final Principal principal = () -> "operator";

    private Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("operator", null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test void summaryUsesOneOrderAggregateAndFourSingleRowPagesInStableSectionOrder() {
        var actor = auth("ADMIN");
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusDays(5);
        when(orders.summarizeOverdueOrders(eq(cutoff), anySet())).thenReturn(List.of(
                new Object[]{"Новый", 2L, today.minusDays(8)},
                new Object[]{"Коррекция", 3L, today.minusDays(9)},
                new Object[]{"Новый", 1L, today.minusDays(6)},
                new Object[]{"Не оплачено", 999L, today.minusDays(99)}));
        var review = new ReviewDTOOne(); review.setPublishedDate(today.minusDays(7));
        when(tasks.loadReviewPage(principal, actor, null, "nagul", 0, 1, "asc", "", cutoff))
                .thenReturn(new PageImpl<>(List.of(review), PageRequest.of(0, 1), 4));
        when(tasks.loadReviewPage(principal, actor, null, "publish", 0, 1, "asc", "", cutoff)).thenReturn(Page.empty());
        var recovery = new ReviewRecoveryTask(); recovery.setScheduledDate(today.minusDays(5));
        when(tasks.loadRecoveryTasks(principal, actor, null, "", 0, 1, "asc", cutoff))
                .thenReturn(new PageImpl<>(List.of(recovery), PageRequest.of(0, 1), 2));
        var bad = new BadReviewTask(); bad.setScheduledDate(today.minusDays(10));
        when(tasks.loadBadReviewTasks(principal, actor, null, "", 0, 1, "asc", cutoff))
                .thenReturn(new PageImpl<>(List.of(bad), PageRequest.of(0, 1), 1));

        var result = query.query(principal, actor);

        assertThat(result.thresholdDays()).isEqualTo(4);
        assertThat(result.total()).isEqualTo(13);
        assertThat(result.statuses()).extracting(section -> section.status())
                .containsExactly("Новые", "Коррекция", "Выгул", "Восстановление", "Плохие");
        assertThat(result.statuses().getFirst().maxDays()).isEqualTo(8);
        assertThatThrownBy(() -> result.statuses().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(orders).summarizeOverdueOrders(eq(cutoff), anySet());
        verify(tasks).loadReviewPage(principal, actor, null, "nagul", 0, 1, "asc", "", cutoff);
        verify(tasks).loadReviewPage(principal, actor, null, "publish", 0, 1, "asc", "", cutoff);
        verify(tasks).loadRecoveryTasks(principal, actor, null, "", 0, 1, "asc", cutoff);
        verify(tasks).loadBadReviewTasks(principal, actor, null, "", 0, 1, "asc", cutoff);
        verifyNoMoreInteractions(orders, tasks);
        verifyNoInteractions(access);
    }

    @Test void missingWorkerStopsBeforeAnyBusinessQuery() {
        var actor = auth("WORKER");
        when(access.resolveWorker(principal)).thenThrow(new WorkerOrderCommandException(
                WorkerOrderCommandException.Kind.NOT_FOUND, "Специалист не найден"));
        assertThatThrownBy(() -> query.query(principal, actor)).isInstanceOf(WorkerOrderCommandException.class)
                .hasMessage("Специалист не найден");
        verifyNoInteractions(orders, tasks);
    }
}
