package com.hunt.otziv.p_products.worker_flow.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.worker_flow.model.WorkerPublicationSession;
import com.hunt.otziv.p_products.worker_flow.model.WorkerPublicationSessionCloseReason;
import com.hunt.otziv.p_products.worker_flow.model.WorkerPublicationSessionStatus;
import com.hunt.otziv.p_products.worker_flow.repository.WorkerPublicationSessionRepository;
import com.hunt.otziv.r_review.board.ReviewBoardMode;
import com.hunt.otziv.r_review.board.ReviewBoardQueryService;
import com.hunt.otziv.r_review.board.ReviewBoardScope;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerPublicationSessionService {

    private static final int DEFAULT_INACTIVITY_MINUTES = 45;
    private static final int DEFAULT_NAGUL_LOOKAHEAD_DAYS = 60;
    private static final String DEFAULT_BUSINESS_ZONE = "Asia/Irkutsk";
    private static final LocalTime DEFAULT_DAY_END_TIME = LocalTime.of(23, 59);
    private static final String NAGUL_BLOCK_MESSAGE = "В разделе \"Выгул\" есть карточки. "
            + "Публикация и раздел \"Все\" откроются после выполнения выгула";

    private final WorkerPublicationSessionRepository repository;
    private final ReviewBoardQueryService reviewBoardQueryService;
    private final AppSettingService appSettingService;
    private final EntityManager entityManager;

    @Transactional
    public SessionDecision evaluateEntry(Worker worker, boolean startSessionIfAllowed) {
        if (!enabled() || worker == null || worker.getId() == null) {
            return SessionDecision.allowed(SessionState.disabled());
        }

        lockWorker(worker.getId());
        ZonedDateTime now = now();
        WorkerPublicationSession session = repository.findByWorkerIdForUpdate(worker.getId()).orElse(null);
        session = validateActiveSession(worker, session, now);
        int nagulCount = nagulCount(worker, now.toLocalDate());

        if (isActive(session)) {
            return SessionDecision.allowed(toState(session, nagulCount, now));
        }
        if (nagulCount > 0) {
            return SessionDecision.blocked(NAGUL_BLOCK_MESSAGE, toState(session, nagulCount, now));
        }

        if (startSessionIfAllowed && availablePublicationCount(worker, now.toLocalDate()) > 0) {
            session = open(worker.getId(), session, now);
        }
        return SessionDecision.allowed(toState(session, nagulCount, now));
    }

    @Transactional
    public SessionState recordActivityAndReevaluate(Worker worker) {
        if (!enabled() || worker == null || worker.getId() == null) {
            return SessionState.disabled();
        }

        lockWorker(worker.getId());
        ZonedDateTime now = now();
        WorkerPublicationSession session = repository.findByWorkerIdForUpdate(worker.getId()).orElse(null);
        session = validateActiveSession(worker, session, now);
        if (!isActive(session)) {
            return toState(session, nagulCount(worker, now.toLocalDate()), now);
        }

        session.setLastActivityAt(now.toLocalDateTime());
        int availablePublications = availablePublicationCount(worker, now.toLocalDate());
        int nagulCount = nagulCount(worker, now.toLocalDate());
        if (availablePublications == 0) {
            close(session, now, nagulCount > 0
                    ? WorkerPublicationSessionCloseReason.NO_AVAILABLE_PUBLICATIONS
                    : WorkerPublicationSessionCloseReason.COMPLETED);
        } else {
            repository.save(session);
        }
        return toState(session, nagulCount, now);
    }

    @Transactional
    public SessionState currentState(Worker worker) {
        if (!enabled() || worker == null || worker.getId() == null) {
            return SessionState.disabled();
        }
        lockWorker(worker.getId());
        ZonedDateTime now = now();
        WorkerPublicationSession session = repository.findByWorkerIdForUpdate(worker.getId()).orElse(null);
        session = validateActiveSession(worker, session, now);
        return toState(session, nagulCount(worker, now.toLocalDate()), now);
    }

    @Scheduled(fixedDelayString = "${worker.publication-session.cleanup-delay-ms:300000}")
    @Transactional
    public void closeExpiredSessions() {
        if (!enabled()) {
            return;
        }
        ZonedDateTime now = now();
        List<WorkerPublicationSession> sessions = repository.findByStatus(WorkerPublicationSessionStatus.ACTIVE);
        for (WorkerPublicationSession session : sessions) {
            WorkerPublicationSessionCloseReason reason = expirationReason(session, now);
            if (reason != null) {
                close(session, now, reason);
            }
        }
    }

    public boolean enabled() {
        return appSettingService.getBoolean(AppSettingService.WORKER_PUBLICATION_SESSION_ENABLED, true);
    }

    private WorkerPublicationSession validateActiveSession(
            Worker worker,
            WorkerPublicationSession session,
            ZonedDateTime now
    ) {
        if (!isActive(session)) {
            return session;
        }
        WorkerPublicationSessionCloseReason expiration = expirationReason(session, now);
        if (expiration != null) {
            close(session, now, expiration);
            return session;
        }

        int availablePublications = availablePublicationCount(worker, now.toLocalDate());
        if (availablePublications == 0) {
            int nagulCount = nagulCount(worker, now.toLocalDate());
            close(session, now, nagulCount > 0
                    ? WorkerPublicationSessionCloseReason.NO_AVAILABLE_PUBLICATIONS
                    : WorkerPublicationSessionCloseReason.COMPLETED);
        }
        return session;
    }

    private WorkerPublicationSession open(
            Long workerId,
            WorkerPublicationSession session,
            ZonedDateTime now
    ) {
        WorkerPublicationSession target = session == null ? new WorkerPublicationSession() : session;
        if (isActive(target)) {
            close(target, now, WorkerPublicationSessionCloseReason.REPLACED);
        }
        target.setWorkerId(workerId);
        target.setStatus(WorkerPublicationSessionStatus.ACTIVE);
        target.setStartedAt(now.toLocalDateTime());
        target.setLastActivityAt(now.toLocalDateTime());
        target.setBusinessDate(businessDate(now));
        target.setClosedAt(null);
        target.setCloseReason(null);
        return repository.save(target);
    }

    private void close(
            WorkerPublicationSession session,
            ZonedDateTime now,
            WorkerPublicationSessionCloseReason reason
    ) {
        if (!isActive(session)) {
            return;
        }
        session.setStatus(WorkerPublicationSessionStatus.CLOSED);
        session.setClosedAt(now.toLocalDateTime());
        session.setCloseReason(reason);
        repository.save(session);
    }

    private WorkerPublicationSessionCloseReason expirationReason(
            WorkerPublicationSession session,
            ZonedDateTime now
    ) {
        if (!isActive(session)) {
            return null;
        }
        if (!businessDate(now).equals(session.getBusinessDate())) {
            return WorkerPublicationSessionCloseReason.DAY_END;
        }
        LocalDateTime inactivityCutoff = now.toLocalDateTime().minusMinutes(inactivityMinutes());
        if (!session.getLastActivityAt().isAfter(inactivityCutoff)) {
            return WorkerPublicationSessionCloseReason.INACTIVITY;
        }
        return null;
    }

    private int nagulCount(Worker worker, LocalDate today) {
        LocalDate lookahead = today.plusDays(Math.max(0, appSettingService.getInt(
                AppSettingService.NAGUL_LOOKAHEAD_DAYS,
                DEFAULT_NAGUL_LOOKAHEAD_DAYS
        )));
        return Math.toIntExact(reviewBoardQueryService.countReviewIdsForBoard(
                ReviewBoardMode.VIGUL,
                ReviewBoardScope.WORKER,
                lookahead,
                null,
                worker,
                null,
                Set.of()
        ));
    }

    private int availablePublicationCount(Worker worker, LocalDate today) {
        return Math.toIntExact(reviewBoardQueryService.countReviewIdsForBoard(
                ReviewBoardMode.PUBLISH,
                ReviewBoardScope.WORKER,
                today,
                null,
                worker,
                null,
                Set.of()
        ));
    }

    private SessionState toState(
            WorkerPublicationSession session,
            int nagulCount,
            ZonedDateTime now
    ) {
        if (session == null) {
            return new SessionState(true, false, null, null, nagulCount, null);
        }
        LocalDateTime expiresAt = isActive(session)
                ? earliest(
                        session.getLastActivityAt().plusMinutes(inactivityMinutes()),
                        businessBoundary(session.getBusinessDate())
                )
                : null;
        return new SessionState(
                true,
                isActive(session),
                session.getStartedAt(),
                expiresAt,
                nagulCount,
                session.getCloseReason() == null ? null : session.getCloseReason().name()
        );
    }

    private LocalDateTime businessBoundary(LocalDate businessDate) {
        return LocalDateTime.of(businessDate, dayEndTime());
    }

    private LocalDate businessDate(ZonedDateTime now) {
        return now.toLocalTime().isBefore(dayEndTime())
                ? now.toLocalDate()
                : now.toLocalDate().plusDays(1);
    }

    private LocalDateTime earliest(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private int inactivityMinutes() {
        return Math.max(1, appSettingService.getInt(
                AppSettingService.WORKER_PUBLICATION_SESSION_INACTIVITY_MINUTES,
                DEFAULT_INACTIVITY_MINUTES
        ));
    }

    private LocalTime dayEndTime() {
        String value = appSettingService.getString(
                AppSettingService.WORKER_PUBLICATION_SESSION_DAY_END_TIME,
                DEFAULT_DAY_END_TIME.toString()
        );
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_DAY_END_TIME;
        }
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(businessZone());
    }

    private ZoneId businessZone() {
        String value = appSettingService.getString(
                AppSettingService.WORKER_PUBLICATION_SESSION_BUSINESS_ZONE,
                DEFAULT_BUSINESS_ZONE
        );
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            log.warn("Invalid worker publication session zone '{}', using {}", value, DEFAULT_BUSINESS_ZONE);
            return ZoneId.of(DEFAULT_BUSINESS_ZONE);
        }
    }

    private boolean isActive(WorkerPublicationSession session) {
        return session != null && session.getStatus() == WorkerPublicationSessionStatus.ACTIVE;
    }

    private void lockWorker(Long workerId) {
        entityManager.find(Worker.class, workerId, LockModeType.PESSIMISTIC_WRITE);
    }

    public record SessionDecision(boolean allowed, String message, SessionState state) {
        public static SessionDecision allowed(SessionState state) {
            return new SessionDecision(true, "", state);
        }

        public static SessionDecision blocked(String message, SessionState state) {
            return new SessionDecision(false, message, state);
        }
    }

    public record SessionState(
            boolean enabled,
            boolean active,
            LocalDateTime startedAt,
            LocalDateTime expiresAt,
            int nagulBlockingCount,
            String closeReason
    ) {
        public static SessionState disabled() {
            return new SessionState(false, false, null, null, 0, null);
        }
    }
}
