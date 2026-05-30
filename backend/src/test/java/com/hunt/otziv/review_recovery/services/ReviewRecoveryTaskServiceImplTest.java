package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.client_messages.ReviewRecoveryNoticeScheduler;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
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
    private ReviewRepository reviewRepository;

    @Mock
    private PersonalReminderService personalReminderService;

    @Mock
    private BotService botService;

    @Mock
    private ReviewRecoveryNoticeScheduler recoveryNoticeScheduler;

    @Mock
    private ReviewRecoveryHoldService recoveryHoldService;

    @InjectMocks
    private ReviewRecoveryTaskServiceImpl service;

    @Test
    void createTaskCreatesBatchCopiesTextBotAndSchedulesToday() {
        User actor = user(1L);
        Review review = review(100L, "старый текст", order(10L), bot(20L));

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

        ReviewRecoveryTask task = service.createTask(100L, actor);

        assertEquals(40L, task.getId());
        assertEquals(ReviewRecoveryTaskStatus.PLANNED, task.getStatus());
        assertEquals(LocalDate.now(), task.getScheduledDate());
        assertEquals("старый текст", task.getOriginalText());
        assertEquals("старый текст", task.getRecoveryText());
        assertEquals("login", task.getBotLoginSnapshot());
        assertEquals("password", task.getBotPasswordSnapshot());
        assertEquals("Бот Ф.", task.getBotFioSnapshot());
        assertSame(actor, task.getCreatedBy());
        assertSame(review.getBot(), task.getBot());

        ArgumentCaptor<ReviewRecoveryBatch> batchCaptor = ArgumentCaptor.forClass(ReviewRecoveryBatch.class);
        verify(batchRepository).save(batchCaptor.capture());
        assertEquals(ReviewRecoveryBatchStatus.OPEN, batchCaptor.getValue().getStatus());
        assertSame(review.getOrderDetails().getOrder(), batchCaptor.getValue().getOrder());
    }

    @Test
    void createTaskSchedulesNextTaskThreeDaysAfterBatchMaxDate() {
        ReviewRecoveryBatch batch = batch(30L, order(10L), ReviewRecoveryBatchStatus.OPEN);
        Review review = review(101L, "следующий текст", batch.getOrder(), null);
        LocalDate previousDate = LocalDate.of(2026, 5, 14);

        when(reviewRepository.findById(101L)).thenReturn(Optional.of(review));
        when(batchRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(eq(10L), anyCollection()))
                .thenReturn(Optional.of(batch));
        when(batchRepository.save(batch)).thenReturn(batch);
        when(taskRepository.maxScheduledDateByBatchId(30L, ReviewRecoveryTaskStatus.CANCELLED)).thenReturn(previousDate);
        when(taskRepository.save(any(ReviewRecoveryTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewRecoveryTask task = service.createTask(101L, user(2L));

        assertEquals(LocalDate.of(2026, 5, 17), task.getScheduledDate());
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
        Review review = review(100L, "текст", order(10L), oldBot);
        review.setFilial(filial);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(40L)
                .order(review.getOrderDetails().getOrder())
                .sourceReview(review)
                .bot(oldBot)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .build();

        when(taskRepository.findById(40L)).thenReturn(Optional.of(task));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of(nextBot));
        when(taskRepository.save(task)).thenReturn(task);

        ReviewRecoveryTask updated = service.changeTaskBot(40L);

        assertSame(nextBot, updated.getBot());
        assertSame(nextBot, review.getBot());
        assertEquals("login", updated.getBotLoginSnapshot());
        assertEquals("password", updated.getBotPasswordSnapshot());
        assertEquals("Бот Ф.", updated.getBotFioSnapshot());
        verify(reviewRepository).save(review);
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

        when(taskRepository.findById(40L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);
        when(taskRepository.countByBatchIdAndStatus(30L, ReviewRecoveryTaskStatus.PLANNED)).thenReturn(0L);
        when(taskRepository.countByBatchIdAndStatus(30L, ReviewRecoveryTaskStatus.DONE)).thenReturn(1L);
        when(batchRepository.save(batch)).thenReturn(batch);

        ReviewRecoveryTask completed = service.completeTask(40L, actor);

        assertEquals(ReviewRecoveryTaskStatus.DONE, completed.getStatus());
        assertEquals(LocalDate.now(), completed.getCompletedDate());
        assertSame(actor, completed.getCompletedBy());
        assertEquals(ReviewRecoveryBatchStatus.COMPLETED, batch.getStatus());
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

        when(taskRepository.findById(40L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);
        when(taskRepository.countByBatchIdAndStatus(30L, ReviewRecoveryTaskStatus.PLANNED)).thenReturn(0L);
        when(taskRepository.countByBatchIdAndStatus(30L, ReviewRecoveryTaskStatus.DONE)).thenReturn(0L);

        ReviewRecoveryTask cancelled = service.cancelTask(40L);

        assertEquals(ReviewRecoveryTaskStatus.CANCELLED, cancelled.getStatus());
        verify(batchRepository, never()).save(batch);
    }

    @Test
    void cancelTaskCannotCancelDoneTask() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(40L)
                .status(ReviewRecoveryTaskStatus.DONE)
                .build();

        when(taskRepository.findById(40L)).thenReturn(Optional.of(task));

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

        when(taskRepository.findById(40L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        ReviewRecoveryTask updated = service.updateTask(40L, "новый текст", "новый ответ", newDate);

        assertEquals("новый текст", updated.getRecoveryText());
        assertEquals("новый ответ", updated.getRecoveryAnswer());
        assertEquals(newDate, updated.getScheduledDate());
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

    private Bot bot(Long id) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setLogin("login");
        bot.setPassword("password");
        bot.setFio("Бот Ф.");
        return bot;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        return user;
    }
}
