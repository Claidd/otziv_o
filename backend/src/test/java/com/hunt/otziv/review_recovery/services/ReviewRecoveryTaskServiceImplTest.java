package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
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
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewRecoveryTaskServiceImplTest {

    @Mock
    private ReviewRecoveryBatchRepository batchRepository;

    @Mock
    private ReviewRecoveryTaskRepository taskRepository;

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
    void createTaskCreatesBatchCopiesTextAndAssignsFreshEligibleBot() {
        User actor = user(1L);
        Bot sourceBot = bot(20L);
        Review review = review(100L, "старый текст", order(10L), sourceBot);
        Worker historicalReviewWorker = new Worker();
        historicalReviewWorker.setId(61L);
        review.setWorker(historicalReviewWorker);
        Bot recoveryBot = prepareRecoveryCandidate(review, 21L);

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
        assertSame(recoveryBot, task.getBot());
        assertSame(review.getOrderDetails().getOrder().getWorker(), task.getWorker());
        assertFalse(sourceBot == task.getBot());

        ArgumentCaptor<ReviewRecoveryBatch> batchCaptor = ArgumentCaptor.forClass(ReviewRecoveryBatch.class);
        verify(batchRepository).save(batchCaptor.capture());
        assertEquals(ReviewRecoveryBatchStatus.OPEN, batchCaptor.getValue().getStatus());
        assertSame(review.getOrderDetails().getOrder(), batchCaptor.getValue().getOrder());
    }

    @Test
    void createTaskSchedulesNextTaskFromOrderRecoveryTail() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.OPEN);
        Review review = review(101L, "следующий текст", batch.getOrder(), null);
        prepareRecoveryCandidate(review, 21L);
        LocalDate previousDate = LocalDate.of(2026, 5, 14);

        when(reviewRepository.findById(101L)).thenReturn(Optional.of(review));
        when(batchRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(eq(10L), anyCollection()))
                .thenReturn(Optional.of(batch));
        when(batchRepository.save(batch)).thenReturn(batch);
        when(recoveryGateService.nextScheduledDate(10L)).thenReturn(previousDate.plusDays(3));
        when(taskRepository.save(any(ReviewRecoveryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewRecoveryTask task = service.createTask(101L, user(2L));

        assertEquals(LocalDate.of(2026, 5, 17), task.getScheduledDate());
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
        Bot recoveryBot = activeWalkedBot(21L, 2);

        when(batchRepository.findFirstByArchiveOrderIdAndStatusInOrderByCreatedAtDesc(eq(10L), anyCollection()))
                .thenReturn(Optional.empty());
        when(batchRepository.save(any(ReviewRecoveryBatch.class))).thenAnswer(invocation -> {
            ReviewRecoveryBatch batch = invocation.getArgument(0);
            batch.setId(30L);
            return batch;
        });
        when(taskRepository.save(any(ReviewRecoveryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of(recoveryBot));
        when(botCooldownService.isAvailableForAssignment(recoveryBot)).thenReturn(true);

        ReviewRecoveryTask task = service.createArchiveTask(source, user(2L));

        assertEquals(today.plusDays(ReviewRecoveryGateService.RECOVERY_SCHEDULE_STEP_DAYS), task.getScheduledDate());
    }

    @Test
    void createTaskReopensCompletedBatchAndDeletesStaleCompletionReminder() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.COMPLETED);
        batch.setCompletedAt(Instant.parse("2026-05-31T06:09:00Z"));
        Review review = review(101L, "следующий текст", batch.getOrder(), null);
        prepareRecoveryCandidate(review, 21L);

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
    void completeTaskMarksBatchCompletedWhenNoPlannedTasksLeft() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.OPEN);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(40L)
                .batch(batch)
                .order(batch.getOrder())
                .sourceReview(review(100L, "текст", batch.getOrder(), null))
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .recoveryText("готовый текст")
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
                null,
                "",
                "",
                "",
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

    private Bot prepareRecoveryCandidate(Review review, Long botId) {
        City city = new City();
        city.setId(3L);
        Filial filial = new Filial();
        filial.setCity(city);
        review.setFilial(filial);

        Bot candidate = activeWalkedBot(botId, 2);
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of(candidate));
        when(botCooldownService.isAvailableForAssignment(candidate)).thenReturn(true);
        return candidate;
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
