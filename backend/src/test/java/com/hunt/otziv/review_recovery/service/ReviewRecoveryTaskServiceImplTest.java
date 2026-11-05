package com.hunt.otziv.review_recovery.service;

import com.hunt.otziv.worker_activity.account_action.WorkerAccountCredentialGuard;
import com.hunt.otziv.worker_activity.account_action.WorkerAccountActionCooldownService;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.archive.dto.ArchiveReviewRecoverySource;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.client_messages.service.ReviewRecoveryNoticeScheduler;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.bot.service.ReviewBotCooldownService;
import com.hunt.otziv.r_review.bot.service.ReviewBotAssignmentGuardService;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewRecoveryTaskServiceImplTest {
    @Mock
    private WorkerAccountActionCooldownService accountActionCooldownService;

    @Mock
    private WorkerAccountCredentialGuard accountCredentialGuard;

    @Mock
    private ReviewRecoveryBatchRepository batchRepository;

    @Mock
    private ReviewRecoveryTaskRepository taskRepository;

    @Mock
    private WorkerRepository workerRepository;

    @Mock
    private ReviewRecoveryBotExclusionService botExclusionService;

    @Mock
    private BadReviewTaskRepository badReviewTaskRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private PersonalReminderService personalReminderService;

    @Mock
    private BotService botService;

    @Mock
    private ReviewRecoveryNoticeScheduler recoveryNoticeScheduler;

    @Mock
    private ReviewRecoveryHoldService recoveryHoldService;

    @Mock
    private ReviewRecoveryGateService recoveryGateService;

    @Mock
    private GamificationEventService gamificationEventService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Mock
    private ReviewBotCooldownService botCooldownService;

    @Mock
    private ReviewBotAssignmentGuardService assignmentGuardService;

    @Mock
    private ReviewAccountWalkScheduleService accountWalkScheduleService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusCheckerService orderStatusCheckerService;

    @Mock
    private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    @Mock
    private ObjectProvider<CommonBillingService> commonBillingServiceProvider;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    @InjectMocks
    private ReviewRecoveryTaskServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void allowSafeAssignments() {
        org.mockito.Mockito.lenient().when(accountWalkScheduleService.walkedCounterThreshold()).thenReturn(2);
        org.mockito.Mockito.lenient().when(accountWalkScheduleService.isWalkedAccount(any()))
                .thenAnswer(invocation -> {
                    Bot bot = invocation.getArgument(0);
                    return bot != null && bot.isActive() && bot.getCounter() >= 2;
                });
        org.mockito.Mockito.lenient().when(assignmentGuardService.scopeForRecoveryTask(
                        nullable(Long.class), nullable(Long.class), nullable(Long.class)))
                .thenAnswer(invocation -> new ReviewBotAssignmentGuardService.AssignmentScope(
                        invocation.getArgument(0), invocation.getArgument(2), null, invocation.getArgument(1)));
        org.mockito.Mockito.lenient().when(assignmentGuardService.blockedBotIds(any())).thenReturn(Set.of());
        org.mockito.Mockito.lenient().when(assignmentGuardService.lockIfEligible(any(), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
    }

    @Test
    void createTaskCreatesBatchCopiesTextAndPreservesPublicationBot() {
        User actor = user(1L);
        Bot sourceBot = bot(20L);
        Review review = review(100L, "старый текст", order(10L), sourceBot);
        Worker historicalReviewWorker = new Worker();
        historicalReviewWorker.setId(61L);
        review.setWorker(historicalReviewWorker);

        when(reviewRepository.findById(100L)).thenReturn(Optional.of(review));
        when(batchRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(eq(10L), anyCollection()))
                .thenReturn(Optional.empty());
        when(batchRepository.save(any(ReviewRecoveryBatch.class))).thenAnswer(invocation -> {
            ReviewRecoveryBatch batch = invocation.getArgument(0);
            batch.setId(30L);
            return batch;
        });
        when(taskRepository.save(any(ReviewRecoveryTask.class))).thenAnswer(invocation -> {
            ReviewRecoveryTask task = invocation.getArgument(0);
            task.setId(40L);
            return task;
        });
        when(recoveryGateService.nextScheduledDate(10L)).thenReturn(LocalDate.of(2026, 5, 17));

        ReviewRecoveryTask task = service.createTask(100L, actor);

        assertEquals(40L, task.getId());
        assertEquals(ReviewRecoveryTaskStatus.PLANNED, task.getStatus());
        assertEquals(LocalDate.of(2026, 5, 17), task.getScheduledDate());
        assertEquals("старый текст", task.getOriginalText());
        assertEquals("старый текст", task.getRecoveryText());
        assertEquals("login", task.getBotLoginSnapshot());
        assertEquals("password", task.getBotPasswordSnapshot());
        assertEquals("Бот Ф.", task.getBotFioSnapshot());
        assertSame(actor, task.getCreatedBy());
        assertSame(sourceBot, task.getBot());
        assertSame(review.getOrderDetails().getOrder().getWorker(), task.getWorker());
        verify(botService, never()).getFindAllByFilialCityId(any());
        verify(botService, never()).getActiveBotsOutsideCityWithCounterAtLeast(any(), anyInt());

        ArgumentCaptor<ReviewRecoveryBatch> batchCaptor = ArgumentCaptor.forClass(ReviewRecoveryBatch.class);
        verify(batchRepository).save(batchCaptor.capture());
        assertEquals(ReviewRecoveryBatchStatus.OPEN, batchCaptor.getValue().getStatus());
        assertSame(review.getOrderDetails().getOrder(), batchCaptor.getValue().getOrder());
    }

    @Test
    void createTaskSchedulesNextTaskFromOrderRecoveryTail() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.OPEN);
        Review review = review(101L, "следующий текст", batch.getOrder(), null);
        LocalDate previousDate = LocalDate.of(2026, 5, 14);

        when(reviewRepository.findById(101L)).thenReturn(Optional.of(review));
        when(batchRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(eq(10L), anyCollection()))
                .thenReturn(Optional.of(batch));
        when(batchRepository.save(batch)).thenReturn(batch);
        when(recoveryGateService.nextScheduledDate(10L)).thenReturn(previousDate.plusDays(3));
        when(taskRepository.save(any(ReviewRecoveryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewRecoveryTask task = service.createTask(101L, user(2L));

        assertEquals(LocalDate.of(2026, 5, 17), task.getScheduledDate());
        assertEquals(ReviewRecoveryTaskStatus.PLANNED, task.getStatus());
        assertEquals(null, task.getBot());
        assertEquals(null, task.getBotLoginSnapshot());
        assertEquals(null, task.getBotPasswordSnapshot());
    }

    @Test
    void createArchiveTaskAllowsLostSourceAccountAndKeepsWorkPending() {
        ArchiveReviewRecoverySource source = org.mockito.Mockito.mock(ArchiveReviewRecoverySource.class);
        when(source.orderId()).thenReturn(10L);
        when(source.reviewId()).thenReturn(125646L);
        when(source.managerId()).thenReturn(null);
        when(source.botId()).thenReturn(null);
        Worker worker = Worker.builder().id(6L).build();
        when(workerRepository.findActiveWorkerIds()).thenReturn(List.of(6L));
        when(workerRepository.findById(6L)).thenReturn(Optional.of(worker));
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ReviewRecoveryTask task = service.createArchiveTask(source, user(1L), 6L);
        assertEquals(ReviewRecoveryTaskStatus.PLANNED, task.getStatus());
        assertSame(worker, task.getWorker());
        assertEquals(null, task.getBot());
        assertTrue(task.getBotLoginSnapshot().isBlank());
        assertTrue(task.getBotPasswordSnapshot().isBlank());
    }

    @Test
    void createArchiveTaskDoesNotScheduleFromPastArchiveDates() {
        LocalDate today = LocalDate.now();
        ArchiveReviewRecoverySource source = archiveSource(
                10L,
                101L,
                today.minusMonths(8),
                today.minusMonths(7),
                today.minusMonths(6)
        );
        Bot publicationBot = bot(20L);

        when(batchRepository.findFirstByArchiveOrderIdAndStatusInOrderByCreatedAtDesc(eq(10L), anyCollection()))
                .thenReturn(Optional.empty());
        when(batchRepository.save(any(ReviewRecoveryBatch.class))).thenAnswer(invocation -> {
            ReviewRecoveryBatch batch = invocation.getArgument(0);
            batch.setId(30L);
            return batch;
        });
        when(taskRepository.save(any(ReviewRecoveryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(botService.findBotById(20L)).thenReturn(publicationBot);

        Worker currentWorker = Worker.builder().id(72L).build();
        when(workerRepository.findActiveWorkerIds()).thenReturn(List.of(72L));
        when(workerRepository.findById(72L)).thenReturn(Optional.of(currentWorker));
        ReviewRecoveryTask task = service.createArchiveTask(source, user(2L), 72L);

        assertEquals(today.plusDays(ReviewRecoveryGateService.RECOVERY_SCHEDULE_STEP_DAYS), task.getScheduledDate());
        assertSame(publicationBot, task.getBot());
        assertSame(currentWorker, task.getWorker());
        assertEquals("login", task.getBotLoginSnapshot());
        assertEquals("password", task.getBotPasswordSnapshot());
        assertEquals("Бот Ф.", task.getBotFioSnapshot());
        verify(botService, never()).getFindAllByFilialCityId(any());
        verify(botService, never()).getActiveBotsOutsideCityWithCounterAtLeast(any(), anyInt());
    }

    @Test
    void createTaskReopensCompletedBatchAndDeletesStaleCompletionReminder() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.COMPLETED);
        batch.setCompletedAt(Instant.parse("2026-05-31T06:09:00Z"));
        Review review = review(101L, "следующий текст", batch.getOrder(), null);

        when(reviewRepository.findById(101L)).thenReturn(Optional.of(review));
        when(batchRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(eq(10L), anyCollection()))
                .thenReturn(Optional.of(batch));
        when(batchRepository.save(batch)).thenReturn(batch);
        when(recoveryGateService.nextScheduledDate(10L)).thenReturn(LocalDate.of(2026, 6, 3));
        when(taskRepository.save(any(ReviewRecoveryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewRecoveryTask task = service.createTask(101L, user(2L));

        assertEquals(ReviewRecoveryBatchStatus.OPEN, batch.getStatus());
        assertEquals(null, batch.getCompletedAt());
        assertEquals(LocalDate.of(2026, 6, 3), task.getScheduledDate());
        verify(personalReminderService).deleteSystemReminder(
                batch.getManager().getUser(),
                "Восстановление завершено: Компания 10",
                "Компания: Компания 10\nЗаказ #10\nЧат: https://chat.example/10\nВсе восстановления завершены, можно написать клиенту."
        );
        verify(personalReminderService).deleteSystemReminderBySource(
                batch.getManager().getUser(),
                PersonalReminderService.SOURCE_REVIEW_RECOVERY_BATCH,
                30L
        );
        verify(personalReminderService).deleteSystemRemindersByTitlePrefixAndTextFragment(
                batch.getManager().getUser(),
                "Восстановление завершено",
                "#10"
        );
    }

    @Test
    void createTaskRejectsDuplicateActiveRecoveryForReview() {
        Review review = review(102L, "текст", order(10L), null);

        when(reviewRepository.findById(102L)).thenReturn(Optional.of(review));
        when(taskRepository.countActiveTasksForReview(eq(102L), anyCollection(), anyCollection())).thenReturn(1L);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createTask(102L, user(3L))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("Задача восстановления уже создана", exception.getReason());
    }

    @Test
    void createTaskRejectsAReviewThatWasNeverPublished() {
        Review review = review(103L, "текст", order(10L), null);
        review.setPublish(false);

        when(reviewRepository.findById(103L)).thenReturn(Optional.of(review));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createTask(103L, user(3L))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("Восстановить можно только опубликованный отзыв", exception.getReason());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void changeTaskBotUpdatesTaskSnapshotsAndSourceReviewBot() {
        City city = new City();
        city.setId(3L);
        Filial filial = new Filial();
        filial.setCity(city);
        Bot oldBot = bot(20L);
        Bot nextBot = bot(21L);
        nextBot.setActive(true);
        nextBot.setCounter(3);
        Review review = review(100L, "текст", order(10L), oldBot);
        review.setFilial(filial);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(40L)
                .order(review.getOrderDetails().getOrder())
                .sourceReview(review)
                .bot(oldBot)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .build();

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of(nextBot));
        when(botCooldownService.isAvailableForAssignment(nextBot)).thenReturn(true);
        when(taskRepository.save(task)).thenReturn(task);

        ReviewRecoveryTask updated = service.changeTaskBot(40L);

        assertSame(nextBot, updated.getBot());
        assertSame(nextBot, review.getBot());
        assertEquals("login", updated.getBotLoginSnapshot());
        assertEquals("password", updated.getBotPasswordSnapshot());
        assertEquals("Бот Ф.", updated.getBotFioSnapshot());
        verify(accountWalkScheduleService).synchronizeAfterAccountChange(review);
        verify(reviewRepository).save(review);
        verify(botCooldownService).markReservedUntilTaskCompletion(nextBot, "review recovery task 40");
    }

    @Test
    void archivedRecoveryWithoutOriginalAccountCanObtainAWalkedAccount() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder().id(1160L).archiveReviewId(125646L)
                .archiveCompanyId(1773L).archiveFilialCityId(3L)
                .status(ReviewRecoveryTaskStatus.PLANNED).build();
        Bot available = activeWalkedBot(21L, 3);
        when(taskRepository.findByIdForMutation(1160L)).thenReturn(Optional.of(task));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of(available));
        when(botCooldownService.isAvailableForAssignment(available)).thenReturn(true);
        when(taskRepository.save(task)).thenReturn(task);

        ReviewRecoveryTask updated = service.changeTaskBot(1160L);

        assertSame(available, updated.getBot());
        assertEquals("login", updated.getBotLoginSnapshot());
        assertEquals("password", updated.getBotPasswordSnapshot());
        assertEquals(ReviewRecoveryTaskStatus.PLANNED, updated.getStatus());
        verify(assignmentGuardService).scopeForRecoveryTask(1773L, 1160L, null);
        verify(assignmentGuardService).lockIfEligible(eq(available), any());
        verify(botCooldownService).markReservedUntilTaskCompletion(available, "review recovery task 1160");
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void archivedRecoveryDoesNotSelectAWalkedAccountWithoutCredentials() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder().id(1160L).archiveReviewId(125646L)
                .archiveCompanyId(1773L).archiveFilialCityId(3L)
                .status(ReviewRecoveryTaskStatus.PLANNED).build();
        Bot noLogin = activeWalkedBot(21L, 3);
        noLogin.setLogin(" ");
        Bot noPassword = activeWalkedBot(22L, 3);
        noPassword.setPassword(null);
        Bot available = activeWalkedBot(23L, 3);
        when(taskRepository.findByIdForMutation(1160L)).thenReturn(Optional.of(task));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of(noLogin, noPassword, available));
        when(botCooldownService.isAvailableForAssignment(available)).thenReturn(true);
        when(taskRepository.save(task)).thenReturn(task);

        assertSame(available, service.changeTaskBot(1160L).getBot());
        verify(assignmentGuardService, never()).lockIfEligible(eq(noLogin), any());
        verify(assignmentGuardService, never()).lockIfEligible(eq(noPassword), any());
    }

    @Test
    void changeTaskBotUsesFreeWalkedSameCityAccountOnly() {
        City city = new City();
        city.setId(3L);
        Filial filial = new Filial();
        filial.setCity(city);
        Bot oldBot = activeWalkedBot(20L, 4);
        Bot underWalkedThreshold = activeWalkedBot(21L, 1);
        Bot rejected = activeWalkedBot(22L, 4);
        Bot occupiedByPublication = activeWalkedBot(23L, 4);
        Bot occupiedByRecovery = activeWalkedBot(24L, 4);
        Bot occupiedByBadTask = activeWalkedBot(25L, 4);
        Bot free = activeWalkedBot(26L, 3);
        Review review = review(100L, "текст", order(10L), oldBot);
        review.setFilial(filial);
        ReviewRecoveryTask task = recoveryTask(40L, review, oldBot);

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        when(botExclusionService.excludedBotIds(40L)).thenReturn(Set.of(rejected.getId()));
        when(reviewRepository.findReservedBotIdsByUnpublishedReviews(100L))
                .thenReturn(Set.of(occupiedByPublication.getId()));
        when(taskRepository.findBotIdsByStatus(ReviewRecoveryTaskStatus.PLANNED, 40L))
                .thenReturn(Set.of(occupiedByRecovery.getId()));
        when(badReviewTaskRepository.findBotIdsByStatus(eq(BadReviewTaskStatus.NEW), isNull()))
                .thenReturn(Set.of(occupiedByBadTask.getId()));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of(
                underWalkedThreshold,
                rejected,
                occupiedByPublication,
                occupiedByRecovery,
                occupiedByBadTask,
                free
        ));
        when(botCooldownService.isAvailableForAssignment(any(Bot.class))).thenReturn(true);
        when(taskRepository.save(task)).thenReturn(task);

        ReviewRecoveryTask updated = service.changeTaskBot(40L);

        assertSame(free, updated.getBot());
        verify(botExclusionService).reject(40L, oldBot, "CHANGE");
        verify(botService, never()).claimReserveBotForCity(any(), anyCollection());
    }

    @Test
    void changeTaskBotPrefersCrossCityCounterThreeToFive() {
        City city = new City();
        city.setId(3L);
        Filial filial = new Filial();
        filial.setCity(city);
        Bot oldBot = activeWalkedBot(20L, 4);
        Bot preferred = activeWalkedBot(21L, 4);
        Bot higherCounter = activeWalkedBot(22L, 8);
        Review review = review(100L, "текст", order(10L), oldBot);
        review.setFilial(filial);
        ReviewRecoveryTask task = recoveryTask(40L, review, oldBot);

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of());
        when(botService.getActiveBotsOutsideCityWithCounterAtLeast(3L, 2))
                .thenReturn(List.of(higherCounter, preferred));
        when(botCooldownService.isAvailableForAssignment(any(Bot.class))).thenReturn(true);
        when(taskRepository.save(task)).thenReturn(task);

        ReviewRecoveryTask updated = service.changeTaskBot(40L);

        assertSame(preferred, updated.getBot());
    }

    @Test
    void changeTaskBotKeepsCurrentAccountAndDoesNotUseReservePoolWhenNoWalkedAccountExists() {
        City city = new City();
        city.setId(3L);
        Filial filial = new Filial();
        filial.setCity(city);
        Bot oldBot = activeWalkedBot(20L, 4);
        Review review = review(100L, "текст", order(10L), oldBot);
        review.setFilial(filial);
        ReviewRecoveryTask task = recoveryTask(40L, review, oldBot);

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of());
        when(botService.getActiveBotsOutsideCityWithCounterAtLeast(3L, 2)).thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeTaskBot(40L)
        );

        assertEquals("Нет свободных выгулянных аккаунтов для восстановления", exception.getReason());
        verify(accountActionCooldownService).admitCurrentAction();
        assertSame(oldBot, task.getBot());
        verify(botExclusionService).reject(40L, oldBot, "CHANGE");
        verify(botService, never()).claimReserveBotForCity(any(), anyCollection());
    }

    @Test
    void blockTaskBotLeavesTaskWithoutAccountWhenNoWalkedReplacementExists() {
        City city = new City();
        city.setId(3L);
        Filial filial = new Filial();
        filial.setCity(city);
        Bot oldBot = activeWalkedBot(20L, 4);
        Review review = review(100L, "текст", order(10L), oldBot);
        review.setFilial(filial);
        ReviewRecoveryTask task = recoveryTask(40L, review, oldBot);

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of());
        when(botService.getActiveBotsOutsideCityWithCounterAtLeast(3L, 2)).thenReturn(List.of());
        when(taskRepository.save(task)).thenReturn(task);

        ReviewRecoveryTask updated = service.deactivateAndChangeTaskBot(40L, 20L);

        assertNull(updated.getBot());
        assertNull(review.getBot());
        assertFalse(oldBot.isActive());
        verify(botExclusionService).reject(40L, oldBot, "BLOCK");
        verify(botService).save(oldBot);
        verify(reviewRepository).save(review);
        verify(botService, never()).claimReserveBotForCity(any(), anyCollection());
    }

    @Test
    void blockTaskBotRejectsBotThatIsNotAttachedBeforeSideEffects() {
        Bot currentBot = activeWalkedBot(20L, 4);
        Review review = review(100L, "текст", order(10L), currentBot);
        ReviewRecoveryTask task = recoveryTask(40L, review, currentBot);

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.deactivateAndChangeTaskBot(40L, 99L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Указанный аккаунт больше не привязан к этой карточке. Обновите данные и повторите действие",
                exception.getReason()
        );
        assertTrue(currentBot.isActive());
        assertSame(currentBot, task.getBot());
        verify(botExclusionService, never()).reject(any(), any(), any());
        verify(accountActionCooldownService, never()).admitCurrentAction();
        verify(botService, never()).save(any());
        verify(taskRepository, never()).save(any());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void missingCredentialsRejectRecoveryBlockBeforeCooldownOrMutation() {
        Bot currentBot = activeWalkedBot(20L, 4);
        Review review = review(100L, "текст", order(10L), currentBot);
        ReviewRecoveryTask task = recoveryTask(40L, review, currentBot);
        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.CONFLICT))
                .when(accountCredentialGuard).assertCurrentActorCanBlock("recovery_task", 40L, 20L);
        assertThrows(ResponseStatusException.class, () -> service.deactivateAndChangeTaskBot(40L, 20L));
        assertTrue(currentBot.isActive());
        verify(accountActionCooldownService, never()).admitCurrentAction();
        verify(botExclusionService, never()).reject(any(), any(), any());
        verify(botService, never()).save(any());
    }

    @Test
    void blockTaskBotRejectsNegativeBotIdBeforeSideEffects() {
        Bot currentBot = activeWalkedBot(20L, 4);
        Review review = review(100L, "текст", order(10L), currentBot);
        ReviewRecoveryTask task = recoveryTask(40L, review, currentBot);
        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.deactivateAndChangeTaskBot(40L, -1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(currentBot.isActive());
        assertSame(currentBot, task.getBot());
        verify(botExclusionService, never()).reject(any(), any(), any());
        verify(botService, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void recoveryWithoutCredentialsCannotBeMarkedDoneOrReleaseItsAccount() {
        for (String[] credentials : new String[][]{{null, null}, {"login", " "}, {"", "password"}}) {
            ReviewRecoveryTask task = ReviewRecoveryTask.builder().id(40L)
                    .status(ReviewRecoveryTaskStatus.PLANNED).recoveryText("готовый текст")
                    .botLoginSnapshot(credentials[0]).botPasswordSnapshot(credentials[1]).build();
            when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));

            ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                    () -> service.completeTask(40L, user(4L)));

            assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
            assertTrue(failure.getReason().contains("Получить аккаунт"));
            assertEquals(ReviewRecoveryTaskStatus.PLANNED, task.getStatus());
            assertNull(task.getCompletedDate());
        }
        verify(taskRepository, never()).save(any());
        verify(botCooldownService, never()).markReleased(any(), any());
        verify(gamificationEventService, never()).recordReviewRecoveryTaskDone(any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    void completeTaskMarksBatchCompletedWhenNoPlannedTasksLeft() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.OPEN);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(40L)
                .batch(batch)
                .order(batch.getOrder())
                .sourceReview(review(100L, "текст", batch.getOrder(), null))
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .recoveryText("готовый текст")
                .botLoginSnapshot("saved-login")
                .botPasswordSnapshot("saved-password")
                .build();
        User actor = user(4L);

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);
        when(taskRepository.countByBatchIdAndStatus(30L, ReviewRecoveryTaskStatus.PLANNED)).thenReturn(0L);
        when(taskRepository.countByBatchIdAndStatus(30L, ReviewRecoveryTaskStatus.DONE)).thenReturn(1L);
        when(batchRepository.save(batch)).thenReturn(batch);
        when(orderRepository.findByIdForMutation(10L)).thenReturn(Optional.of(batch.getOrder()));

        ReviewRecoveryTask completed = service.completeTask(40L, actor);

        assertEquals(ReviewRecoveryTaskStatus.DONE, completed.getStatus());
        assertEquals(LocalDate.now(), completed.getCompletedDate());
        assertSame(actor, completed.getCompletedBy());
        assertEquals(ReviewRecoveryBatchStatus.COMPLETED, batch.getStatus());
        verify(botExclusionService).clearForTask(40L);
        verify(batchRepository).save(batch);
        verify(personalReminderService).createSystemReminderDueNow(
                batch.getManager().getUser(),
                "Восстановление завершено: Компания 10",
                "Компания: Компания 10\nЗаказ #10\nЧат: https://chat.example/10\nВсе восстановления завершены, можно написать клиенту.",
                PersonalReminderService.SOURCE_REVIEW_RECOVERY_BATCH,
                30L,
                10L
        );
    }

    @Test
    void cancelTaskMarksPlannedTaskCancelled() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.OPEN);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(40L)
                .batch(batch)
                .order(batch.getOrder())
                .sourceReview(review(100L, "текст", batch.getOrder(), null))
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .build();

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);
        when(taskRepository.countByBatchIdAndStatus(30L, ReviewRecoveryTaskStatus.PLANNED)).thenReturn(0L);
        when(taskRepository.countByBatchIdAndStatus(30L, ReviewRecoveryTaskStatus.DONE)).thenReturn(0L);

        ReviewRecoveryTask cancelled = service.cancelTask(40L);

        assertEquals(ReviewRecoveryTaskStatus.CANCELLED, cancelled.getStatus());
        verify(botExclusionService).clearForTask(40L);
        verify(batchRepository, never()).save(batch);
    }

    @Test
    void cancelTaskCannotCancelDoneTask() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(40L)
                .status(ReviewRecoveryTaskStatus.DONE)
                .build();

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));

        assertThrows(ResponseStatusException.class, () -> service.cancelTask(40L));
        verify(taskRepository, never()).save(any(ReviewRecoveryTask.class));
    }

    @Test
    void markClientNotifiedDeletesNewAndLegacyRecoveryReminder() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.COMPLETED);
        User actor = user(9L);

        when(batchRepository.findById(30L)).thenReturn(Optional.of(batch));
        when(batchRepository.save(batch)).thenReturn(batch);

        ReviewRecoveryBatch notified = service.markClientNotified(30L, actor);

        assertEquals(ReviewRecoveryBatchStatus.CLIENT_NOTIFIED, notified.getStatus());
        assertSame(actor, notified.getClientNotifiedBy());
        verify(personalReminderService).deleteSystemReminder(
                batch.getManager().getUser(),
                "Восстановление завершено: Компания 10",
                "Компания: Компания 10\nЗаказ #10\nЧат: https://chat.example/10\nВсе восстановления завершены, можно написать клиенту."
        );
        verify(personalReminderService).deleteSystemReminderBySource(
                batch.getManager().getUser(),
                PersonalReminderService.SOURCE_REVIEW_RECOVERY_BATCH,
                30L
        );
        verify(personalReminderService).deleteSystemRemindersByTitlePrefixAndTextFragment(
                batch.getManager().getUser(),
                "Восстановление завершено",
                "#10"
        );
    }

    @Test
    void updateTaskStoresEditableTextAndDate() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(40L)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .recoveryText("старый текст")
                .scheduledDate(LocalDate.of(2026, 5, 14))
                .build();
        LocalDate newDate = LocalDate.of(2026, 5, 20);

        when(taskRepository.findByIdForMutation(40L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        ReviewRecoveryTask updated = service.updateTask(40L, "новый текст", "новый ответ", newDate);

        assertEquals("новый текст", updated.getRecoveryText());
        assertEquals("новый ответ", updated.getRecoveryAnswer());
        assertEquals(newDate, updated.getScheduledDate());
        verify(taskRepository).findByIdForMutation(40L);
        verify(taskRepository, never()).findById(40L);
    }

    @Test
    void updateTaskAuditsMovedPlannedDateWithCommandActor() {
        Worker worker = Worker.builder().id(6L).build();
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(1160L)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .recoveryText("старый текст")
                .worker(worker)
                .archiveOrderId(900L)
                .archiveReviewId(901L)
                .scheduledDate(LocalDate.of(2026, 11, 1))
                .build();
        LocalDate newDate = LocalDate.of(2026, 11, 2);
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken(
                "manager-anna", "", "ROLE_MANAGER");
        when(taskRepository.findByIdForMutation(1160L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        service.updateTask(1160L, "текст", null, newDate, auth);

        verify(businessAuditService).recordSafely(
                eq(auth),
                eq(ReviewRecoveryTaskServiceImpl.ACTION_TASK_DATE_CHANGED),
                eq("review_recovery_task"),
                eq(1160L),
                eq(900L),
                eq(901L),
                eq(LocalDate.of(2026, 11, 1)),
                eq(newDate),
                eq("Плановая дата задачи восстановления изменена; специалист #6")
        );
    }

    @Test
    void updateTaskWithoutCapturedActorStillAuditsMovedDate() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(41L)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .recoveryText("старый текст")
                .scheduledDate(LocalDate.of(2026, 5, 14))
                .build();
        when(taskRepository.findByIdForMutation(41L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        service.updateTask(41L, "текст", null, LocalDate.of(2026, 5, 20));

        verify(businessAuditService).recordSafely(
                eq(ReviewRecoveryTaskServiceImpl.ACTION_TASK_DATE_CHANGED),
                eq("review_recovery_task"),
                eq(41L),
                isNull(),
                isNull(),
                eq(LocalDate.of(2026, 5, 14)),
                eq(LocalDate.of(2026, 5, 20)),
                eq("Плановая дата задачи восстановления изменена")
        );
    }

    @Test
    void updateTaskWithUnchangedOrMissingDateWritesNoDateAudit() {
        LocalDate date = LocalDate.of(2026, 5, 14);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(42L)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .recoveryText("старый текст")
                .scheduledDate(date)
                .build();
        when(taskRepository.findByIdForMutation(42L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        service.updateTask(42L, "текст", null, date);
        service.updateTask(42L, "текст 2", null, null);

        assertEquals(date, task.getScheduledDate());
        verify(businessAuditService, never()).recordSafely(
                eq(ReviewRecoveryTaskServiceImpl.ACTION_TASK_DATE_CHANGED),
                any(), any(), any(), any(), any(), any(), any());
        verify(businessAuditService, never()).recordSafely(
                any(org.springframework.security.core.Authentication.class),
                eq(ReviewRecoveryTaskServiceImpl.ACTION_TASK_DATE_CHANGED),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getTaskUsesReadOnlyLookupWithoutPessimisticWriteLock() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder().id(40L).build();
        when(taskRepository.findById(40L)).thenReturn(Optional.of(task));

        ReviewRecoveryTask loaded = service.getTask(40L);

        assertSame(task, loaded);
        verify(taskRepository).findById(40L);
        verify(taskRepository, never()).findByIdForMutation(40L);
    }

    @Test
    void getTasksByOrderIdLoadsVisibleNonCancelledTasks() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder().id(40L).build();
        ReviewRecoveryTask cancelled = ReviewRecoveryTask.builder()
                .id(41L)
                .status(ReviewRecoveryTaskStatus.CANCELLED)
                .build();

        when(taskRepository.findByOrderIdAndBatchStatusIn(eq(10L), anyCollection()))
                .thenReturn(List.of(task, cancelled));

        List<ReviewRecoveryTask> tasks = service.getTasksByOrderId(10L);

        assertEquals(List.of(task), tasks);
    }

    @Test
    void belongsChecksDelegateToRepositories() {
        when(taskRepository.existsByIdAndOrderId(40L, 10L)).thenReturn(true);
        when(batchRepository.existsByIdAndOrderId(30L, 10L)).thenReturn(true);

        assertEquals(true, service.taskBelongsToOrder(40L, 10L));
        assertEquals(true, service.batchBelongsToOrder(30L, 10L));
        assertEquals(false, service.taskBelongsToOrder(null, 10L));
        assertEquals(false, service.batchBelongsToOrder(30L, null));
    }

    @Test
    void archiveClientNotifiedBeforeDelegatesToBulkRepositoryUpdate() {
        Instant cutoff = Instant.parse("2025-11-15T00:00:00Z");
        Instant archivedAt = Instant.parse("2026-05-14T00:00:00Z");

        when(batchRepository.archiveClientNotifiedBatches(
                ReviewRecoveryBatchStatus.CLIENT_NOTIFIED,
                ReviewRecoveryBatchStatus.ARCHIVED,
                cutoff,
                archivedAt
        )).thenReturn(2);

        int archived = service.archiveClientNotifiedBefore(cutoff, archivedAt);

        assertEquals(2, archived);
        verify(batchRepository).archiveClientNotifiedBatches(
                ReviewRecoveryBatchStatus.CLIENT_NOTIFIED,
                ReviewRecoveryBatchStatus.ARCHIVED,
                cutoff,
                archivedAt
        );
    }

    @Test
    void reassignPendingTasksForOrderUsesOneBulkQuery() {
        Worker worker = new Worker();
        worker.setId(77L);
        when(taskRepository.reassignWorkerByOrderIdAndStatus(
                10L,
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                77L,
                worker
        )).thenReturn(2);

        int updated = service.reassignPendingTasksForOrder(10L, worker);

        assertEquals(2, updated);
        verify(taskRepository).reassignWorkerByOrderIdAndStatus(
                10L,
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                77L,
                worker
        );
    }

    @Test
    void archiveTransferSkipsTaskCompletedBeforeLockAndKeepsHistoricalSource() {
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken("admin", "", "ROLE_ADMIN");
        Worker replacement = Worker.builder().id(72L).build();
        Worker former = Worker.builder().id(60L).build();
        ReviewRecoveryBatch batch = ReviewRecoveryBatch.builder().status(ReviewRecoveryBatchStatus.OPEN).build();
        ReviewRecoveryTask pending = ReviewRecoveryTask.builder().id(1238L).archiveOrderId(10L).archiveReviewId(11L)
                .worker(former).batch(batch).status(ReviewRecoveryTaskStatus.PLANNED).build();
        ReviewRecoveryTask completed = ReviewRecoveryTask.builder().id(1239L).archiveOrderId(10L).worker(former)
                .batch(batch).status(ReviewRecoveryTaskStatus.DONE).build();
        when(workerRepository.findActiveWorkerIds()).thenReturn(List.of(72L));
        when(workerRepository.findById(72L)).thenReturn(Optional.of(replacement));
        when(taskRepository.findPendingArchiveTaskIds(10L)).thenReturn(List.of(1238L, 1239L));
        when(taskRepository.findByIdForMutation(1238L)).thenReturn(Optional.of(pending));
        when(taskRepository.findByIdForMutation(1239L)).thenReturn(Optional.of(completed));

        assertEquals(1, service.reassignArchiveTasks(10L, null, 72L, auth));
        assertSame(replacement, pending.getWorker());
        assertSame(former, completed.getWorker());
        assertEquals(10L, pending.getArchiveOrderId());
        assertEquals(11L, pending.getArchiveReviewId());
        assertNull(pending.getOrder());
        verify(assignmentMutationGuardService).assertRecoveryTask(1238L, auth);
        verify(taskRepository, never()).save(completed);
        verify(taskRepository).flush();
    }

    @Test
    void archiveTransferRejectsTaskFromAnotherOrder() {
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken("admin", "", "ROLE_ADMIN");
        when(workerRepository.findActiveWorkerIds()).thenReturn(List.of(72L));
        when(workerRepository.findById(72L)).thenReturn(Optional.of(Worker.builder().id(72L).build()));
        when(taskRepository.findPendingArchiveTaskIds(10L)).thenReturn(List.of(1238L));
        assertThrows(ResponseStatusException.class, () -> service.reassignArchiveTasks(10L, 999L, 72L, auth));
        verify(taskRepository, never()).save(any());
    }

    @Test
    void archiveCreationRejectsInactiveWorkerBeforeCreatingBatch() {
        ArchiveReviewRecoverySource source = archiveSource(10L, 11L, null, null, null);
        when(workerRepository.findActiveWorkerIds()).thenReturn(List.of(72L));
        assertThrows(ResponseStatusException.class, () -> service.createArchiveTask(source, user(2L), 60L));
        verify(batchRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    private Order order(Long id) {
        Order order = new Order();
        order.setId(id);
        Manager manager = new Manager();
        manager.setId(50L);
        manager.setUser(user(5L));
        order.setManager(manager);
        Worker worker = new Worker();
        worker.setId(60L);
        order.setWorker(worker);
        Company company = new Company();
        company.setId(70L);
        company.setTitle("Компания " + id);
        company.setUrlChat("https://chat.example/" + id);
        order.setCompany(company);
        return order;
    }

    private ReviewRecoveryBatch batch(Long id, Order order, ReviewRecoveryBatchStatus status) {
        ReviewRecoveryBatch batch = new ReviewRecoveryBatch();
        batch.setId(id);
        batch.setOrder(order);
        batch.setManager(order.getManager());
        batch.setStatus(status);
        return batch;
    }

    private Review review(Long id, String text, Order order, Bot bot) {
        OrderDetails details = new OrderDetails();
        details.setOrder(order);
        Review review = new Review();
        review.setId(id);
        review.setText(text);
        review.setAnswer("ответ");
        review.setOrderDetails(details);
        review.setWorker(order.getWorker());
        review.setBot(bot);
        // Only a review that was actually published can be lost and recovered.
        review.setPublish(true);
        return review;
    }

    private ArchiveReviewRecoverySource archiveSource(
            Long orderId,
            Long reviewId,
            LocalDate created,
            LocalDate changed,
            LocalDate publishedDate
    ) {
        return new ArchiveReviewRecoverySource(
                orderId,
                reviewId,
                20L,
                null,
                "Архив",
                "Компания",
                "",
                "",
                "",
                null,
                null,
                20L,
                "archive-login",
                "archive-password",
                "Архивный Бот",
                3L,
                "Иркутск",
                "Филиал",
                "",
                "Категория",
                "Подкатегория",
                null,
                "Продукт",
                "архивный текст",
                "",
                created,
                changed,
                publishedDate,
                false,
                false,
                null,
                ""
        );
    }

    private Bot bot(Long id) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setLogin("login");
        bot.setPassword("password");
        bot.setFio("Бот Ф.");
        return bot;
    }

    private Bot activeWalkedBot(Long id, int counter) {
        Bot bot = bot(id);
        bot.setActive(true);
        bot.setCounter(counter);
        return bot;
    }

    private ReviewRecoveryTask recoveryTask(Long id, Review review, Bot bot) {
        return ReviewRecoveryTask.builder()
                .id(id)
                .order(review.getOrderDetails().getOrder())
                .sourceReview(review)
                .bot(bot)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .build();
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        return user;
    }
}
