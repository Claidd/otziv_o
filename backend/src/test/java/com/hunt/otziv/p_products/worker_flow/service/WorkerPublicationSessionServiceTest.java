package com.hunt.otziv.p_products.worker_flow.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.worker_flow.model.WorkerPublicationSession;
import com.hunt.otziv.p_products.worker_flow.model.WorkerPublicationSessionCloseReason;
import com.hunt.otziv.p_products.worker_flow.model.WorkerPublicationSessionStatus;
import com.hunt.otziv.p_products.worker_flow.repository.WorkerPublicationSessionRepository;
import com.hunt.otziv.r_review.board.model.ReviewBoardMode;
import com.hunt.otziv.r_review.board.service.ReviewBoardQueryService;
import com.hunt.otziv.r_review.board.model.ReviewBoardScope;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WorkerPublicationSessionServiceTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");

    @Mock
    private WorkerPublicationSessionRepository repository;
    @Mock
    private ReviewBoardQueryService reviewBoardQueryService;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private EntityManager entityManager;

    private WorkerPublicationSessionService service;
    private Worker worker;

    @BeforeEach
    void setUp() {
        service = new WorkerPublicationSessionService(repository, reviewBoardQueryService, appSettingService, entityManager);
        worker = new Worker();
        worker.setId(42L);

        when(appSettingService.getBoolean(AppSettingService.WORKER_PUBLICATION_SESSION_ENABLED, true))
                .thenReturn(true);
        when(appSettingService.getString(
                AppSettingService.WORKER_PUBLICATION_SESSION_BUSINESS_ZONE,
                "Asia/Irkutsk"
        )).thenReturn("Asia/Irkutsk");
        lenient().when(appSettingService.getString(
                AppSettingService.WORKER_PUBLICATION_SESSION_DAY_END_TIME,
                "23:59"
        )).thenReturn("23:59");
        lenient().when(appSettingService.getInt(
                AppSettingService.WORKER_PUBLICATION_SESSION_INACTIVITY_MINUTES,
                45
        )).thenReturn(45);
        when(appSettingService.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 60)).thenReturn(60);
    }

    @Test
    void blocksPublicationWhenNagulExistsBeforeSession() {
        LocalDate lookahead = LocalDate.now(BUSINESS_ZONE).plusDays(60);
        when(repository.findByWorkerIdForUpdate(42L)).thenReturn(Optional.empty());
        when(boardCount(ReviewBoardMode.VIGUL, lookahead)).thenReturn(2L);

        WorkerPublicationSessionService.SessionDecision decision = service.evaluateEntry(worker, true);

        assertFalse(decision.allowed());
        assertEquals(2, decision.state().nagulBlockingCount());
        assertEquals("В разделе \"Выгул\" есть карточки. "
                + "Публикация и раздел \"Все\" откроются после выполнения выгула", decision.message());
    }

    @Test
    void opensSessionWhenNagulIsEmptyAndPublicationIsAvailable() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        when(repository.findByWorkerIdForUpdate(42L)).thenReturn(Optional.empty());
        when(boardCount(ReviewBoardMode.VIGUL, today.plusDays(60))).thenReturn(0L);
        when(boardCount(ReviewBoardMode.PUBLISH, today)).thenReturn(3L);
        when(repository.save(any(WorkerPublicationSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerPublicationSessionService.SessionDecision decision = service.evaluateEntry(worker, true);

        assertTrue(decision.allowed());
        assertTrue(decision.state().active());
        verify(repository).save(any(WorkerPublicationSession.class));
    }

    @Test
    void newNagulDoesNotInterruptActiveSessionWhilePublicationRemains() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        WorkerPublicationSession session = activeSession(today);
        when(repository.findByWorkerIdForUpdate(42L)).thenReturn(Optional.of(session));
        when(boardCount(ReviewBoardMode.PUBLISH, today)).thenReturn(1L);
        when(boardCount(ReviewBoardMode.VIGUL, today.plusDays(60))).thenReturn(1L);

        WorkerPublicationSessionService.SessionDecision decision = service.evaluateEntry(worker, true);

        assertTrue(decision.allowed());
        assertTrue(decision.state().active());
        assertEquals(1, decision.state().nagulBlockingCount());
    }

    @Test
    void closesSessionWhenOnlyNagulRemains() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        WorkerPublicationSession session = activeSession(today);
        when(repository.findByWorkerIdForUpdate(42L)).thenReturn(Optional.of(session));
        when(boardCount(ReviewBoardMode.PUBLISH, today)).thenReturn(0L);
        when(boardCount(ReviewBoardMode.VIGUL, today.plusDays(60))).thenReturn(2L);
        when(repository.save(session)).thenReturn(session);

        WorkerPublicationSessionService.SessionDecision decision = service.evaluateEntry(worker, true);

        assertFalse(decision.allowed());
        assertEquals(WorkerPublicationSessionStatus.CLOSED, session.getStatus());
        assertEquals(WorkerPublicationSessionCloseReason.NO_AVAILABLE_PUBLICATIONS, session.getCloseReason());
    }

    @Test
    void closesSessionAfterInactivity() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        WorkerPublicationSession session = activeSession(today);
        session.setLastActivityAt(LocalDateTime.now(BUSINESS_ZONE).minusMinutes(46));
        when(repository.findByWorkerIdForUpdate(42L)).thenReturn(Optional.of(session));
        when(boardCount(ReviewBoardMode.VIGUL, today.plusDays(60))).thenReturn(1L);
        when(repository.save(session)).thenReturn(session);

        WorkerPublicationSessionService.SessionDecision decision = service.evaluateEntry(worker, true);

        assertFalse(decision.allowed());
        assertFalse(decision.state().active());
        assertEquals(WorkerPublicationSessionCloseReason.INACTIVITY, session.getCloseReason());
    }

    @Test
    void closesPreviousBusinessDaySession() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        WorkerPublicationSession session = activeSession(today.minusDays(1));
        when(repository.findByWorkerIdForUpdate(42L)).thenReturn(Optional.of(session));
        when(boardCount(ReviewBoardMode.VIGUL, today.plusDays(60))).thenReturn(1L);
        when(repository.save(session)).thenReturn(session);

        WorkerPublicationSessionService.SessionDecision decision = service.evaluateEntry(worker, true);

        assertFalse(decision.allowed());
        assertEquals(WorkerPublicationSessionCloseReason.DAY_END, session.getCloseReason());
    }

    private WorkerPublicationSession activeSession(LocalDate businessDate) {
        WorkerPublicationSession session = new WorkerPublicationSession();
        session.setWorkerId(42L);
        session.setStatus(WorkerPublicationSessionStatus.ACTIVE);
        session.setBusinessDate(businessDate);
        session.setStartedAt(LocalDateTime.now(BUSINESS_ZONE).minusMinutes(5));
        session.setLastActivityAt(LocalDateTime.now(BUSINESS_ZONE).minusMinutes(1));
        return session;
    }

    private long boardCount(ReviewBoardMode mode, LocalDate date) {
        return reviewBoardQueryService.countReviewIdsForBoard(
                mode,
                ReviewBoardScope.WORKER,
                date,
                null,
                worker,
                null,
                java.util.Set.of()
        );
    }
}
