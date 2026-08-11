package com.hunt.otziv.p_products.worker_flow.service;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class WorkerPublicationGateServiceTest {

    @Mock
    private OrderService orderService;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Mock
    private ReviewRecoveryTaskService reviewRecoveryTaskService;

    @Mock
    private UserService userService;

    @Mock
    private WorkerService workerService;

    @Mock
    private WorkerFlowLockService workerFlowLockService;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private WorkerPublicationSessionService publicationSessionService;

    private WorkerPublicationGateService service;
    private Principal principal;
    private Authentication workerAuth;
    private Worker worker;

    @BeforeEach
    void setUp() {
        principal = () -> "worker";
        workerAuth = new UsernamePasswordAuthenticationToken(
                "worker",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
        );

        User user = new User();
        user.setId(77L);
        worker = new Worker();
        worker.setId(88L);

        service = new WorkerPublicationGateService(
                orderService,
                badReviewTaskService,
                reviewRecoveryTaskService,
                userService,
                workerService,
                workerFlowLockService,
                appSettingService,
                publicationSessionService
        );

        when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        when(workerService.getWorkerByUserId(77L)).thenReturn(worker);
        when(orderService.countActionableOrdersByStatusToWorker(worker)).thenReturn(Map.of());
        when(publicationSessionService.evaluateEntry(eq(worker), anyBoolean())).thenReturn(
                WorkerPublicationSessionService.SessionDecision.allowed(
                        WorkerPublicationSessionService.SessionState.disabled()
                )
        );
    }

    @Test
    void badTaskOverdueTriggersLockUntilAllSpecialTasksDueTodayAreClosed() {
        LocalDate today = LocalDate.now();
        LocalDate overdueCutoff = today.minusDays(3);

        when(appSettingService.getBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false))
                .thenReturn(true);
        when(reviewRecoveryTaskService.countDueTasksToWorker(worker, today)).thenReturn(1, 1, 0);
        when(reviewRecoveryTaskService.countDueTasksToWorker(worker, overdueCutoff)).thenReturn(0, 0, 0);
        when(badReviewTaskService.countDueTasksToWorker(worker, today)).thenReturn(2, 0, 0);
        when(badReviewTaskService.countDueTasksToWorker(worker, overdueCutoff)).thenReturn(1, 0, 0);
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, false, false)).thenReturn(false);
        when(workerFlowLockService.syncPublicationLock("worker:88:special-tasks", 88L, true, true)).thenReturn(true);
        when(workerFlowLockService.syncPublicationLock("worker:88:special-tasks", 88L, true, false)).thenReturn(true);
        when(workerFlowLockService.syncPublicationLock("worker:88:special-tasks", 88L, false, false)).thenReturn(false);

        Optional<WorkerPublicationGateService.PublicationBlock> firstBlock = service.redirectFor(
                principal,
                workerAuth,
                WorkerPublicationGateService.SECTION_PUBLISH
        );

        assertTrue(firstBlock.isPresent());
        assertEquals(WorkerPublicationGateService.SECTION_BAD, firstBlock.get().section());

        Optional<WorkerPublicationGateService.PublicationBlock> secondBlock = service.redirectFor(
                principal,
                workerAuth,
                WorkerPublicationGateService.SECTION_PUBLISH
        );

        assertTrue(secondBlock.isPresent());
        assertEquals(WorkerPublicationGateService.SECTION_RECOVERY, secondBlock.get().section());

        Optional<WorkerPublicationGateService.PublicationBlock> thirdBlock = service.redirectFor(
                principal,
                workerAuth,
                WorkerPublicationGateService.SECTION_PUBLISH
        );

        assertTrue(thirdBlock.isEmpty());
    }

    @Test
    void nagulSessionDecisionRedirectsPublicationToNagul() {
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, false, false)).thenReturn(false);
        when(publicationSessionService.evaluateEntry(worker, true)).thenReturn(
                WorkerPublicationSessionService.SessionDecision.blocked(
                        "Сначала выполните выгул",
                        new WorkerPublicationSessionService.SessionState(true, false, null, null, 2, null)
                )
        );

        Optional<WorkerPublicationGateService.PublicationBlock> block = service.redirectFor(
                principal,
                workerAuth,
                WorkerPublicationGateService.SECTION_PUBLISH
        );

        assertTrue(block.isPresent());
        assertEquals(WorkerPublicationGateService.SECTION_NAGUL, block.get().section());
        assertEquals("Сначала выполните выгул", block.get().message());
    }
}
