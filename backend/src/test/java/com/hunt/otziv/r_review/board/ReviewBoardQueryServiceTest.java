package com.hunt.otziv.r_review.board;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewBoardQueryServiceTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private TypedQuery<Long> idQuery;

    @Mock
    private TypedQuery<Long> countQuery;

    @Test
    void reviewPageableClampsValuesAndSortsByPublishedDateThenId() {
        ReviewBoardQueryService service = new ReviewBoardQueryService(entityManager);

        Pageable pageable = service.reviewPageable(-2, 0, "desc");

        assertEquals(0, pageable.getPageNumber());
        assertEquals(1, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("publishedDate").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
    }

    @Test
    void findReviewIdsForBoardReturnsEmptyForManagerWithoutWorkers() {
        ReviewBoardQueryService service = new ReviewBoardQueryService(entityManager);

        Page<Long> page = service.findReviewIdsForBoard(
                ReviewBoardMode.PUBLISH,
                ReviewBoardScope.MANAGER,
                LocalDate.of(2026, 5, 4),
                null,
                null,
                new Manager(),
                Set.of(),
                "42",
                0,
                10,
                "asc"
        );

        assertTrue(page.isEmpty());
        assertEquals(0, page.getTotalElements());
        verifyNoInteractions(entityManager);
    }

    @Test
    void findReviewIdsForBoardBindsKeywordAndWorkerScopeParameters() {
        ReviewBoardQueryService service = new ReviewBoardQueryService(entityManager);
        Worker worker = new Worker();

        when(entityManager.createQuery(
                argThat((String query) -> query != null && query.startsWith("SELECT DISTINCT r.id")),
                eq(Long.class)
        )).thenReturn(idQuery);
        when(entityManager.createQuery(
                argThat((String query) -> query != null && query.startsWith("SELECT COUNT(DISTINCT r.id)")),
                eq(Long.class)
        )).thenReturn(countQuery);
        stubQueryParameters(idQuery);
        stubQueryParameters(countQuery);
        when(idQuery.setFirstResult(anyInt())).thenReturn(idQuery);
        when(idQuery.setMaxResults(anyInt())).thenReturn(idQuery);
        when(idQuery.getResultList()).thenReturn(List.of(10L, 20L));
        when(countQuery.getSingleResult()).thenReturn(2L);

        Page<Long> page = service.findReviewIdsForBoard(
                ReviewBoardMode.ORDER_STATUS,
                ReviewBoardScope.WORKER,
                null,
                "Коррекция",
                worker,
                null,
                null,
                "42",
                0,
                10,
                "desc"
        );

        assertEquals(List.of(10L, 20L), page.getContent());
        assertEquals(2, page.getTotalElements());
        verify(idQuery).setFirstResult(0);
        verify(idQuery).setMaxResults(10);
        verify(idQuery).setParameter(eq("status"), eq("Коррекция"));
        verify(idQuery).setParameter(eq("worker"), same(worker));
        verify(idQuery).setParameter(eq("keyword"), eq("%42%"));
        verify(idQuery).setParameter(eq("keywordLong"), eq(42L));
        verify(idQuery, never()).setParameter(eq("keywordUuid"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void countReviewIdsForBoardBindsManagerVigulScopeParameters() {
        ReviewBoardQueryService service = new ReviewBoardQueryService(entityManager);
        LocalDate localDate = LocalDate.of(2026, 5, 4);
        Manager manager = new Manager();
        Set<Worker> workers = Set.of(new Worker());
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);

        when(entityManager.createQuery(queryCaptor.capture(), eq(Long.class))).thenReturn(countQuery);
        stubQueryParameters(countQuery);
        when(countQuery.getSingleResult()).thenReturn(7L);

        long count = service.countReviewIdsForBoard(
                ReviewBoardMode.VIGUL,
                ReviewBoardScope.MANAGER,
                localDate,
                null,
                null,
                manager,
                workers
        );

        assertEquals(7L, count);
        assertTrue(queryCaptor.getValue().contains("LEFT JOIN r.bot b"));
        assertTrue(queryCaptor.getValue().contains("(o IS NULL OR o.manager IS NULL OR o.manager = :manager)"));
        verify(countQuery).setParameter(eq("localDate"), eq(localDate));
        verify(countQuery).setParameter(eq("workers"), same(workers));
        verify(countQuery).setParameter(eq("manager"), same(manager));
        verify(countQuery, never()).setParameter(eq("status"), org.mockito.ArgumentMatchers.any());
    }

    private void stubQueryParameters(TypedQuery<Long> query) {
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
    }
}
