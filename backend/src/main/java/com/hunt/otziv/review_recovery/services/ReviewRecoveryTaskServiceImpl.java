package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
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
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewRecoveryTaskServiceImpl implements ReviewRecoveryTaskService {

    private static final int SCHEDULE_STEP_DAYS = 3;
    private static final EnumSet<ReviewRecoveryBatchStatus> ACTIVE_BATCH_STATUSES =
            EnumSet.of(ReviewRecoveryBatchStatus.OPEN, ReviewRecoveryBatchStatus.COMPLETED);
    private static final EnumSet<ReviewRecoveryTaskStatus> ACTIVE_TASK_STATUSES =
            EnumSet.of(ReviewRecoveryTaskStatus.PLANNED, ReviewRecoveryTaskStatus.DONE);
    private static final EnumSet<ReviewRecoveryBatchStatus> VISIBLE_BATCH_STATUSES =
            EnumSet.of(ReviewRecoveryBatchStatus.OPEN, ReviewRecoveryBatchStatus.COMPLETED);
    private final SecureRandom random = new SecureRandom();

    private final ReviewRecoveryBatchRepository batchRepository;
    private final ReviewRecoveryTaskRepository taskRepository;
    private final ReviewRepository reviewRepository;
    private final PersonalReminderService personalReminderService;
    private final BotService botService;

    @Override
    @Transactional(readOnly = true)
    public List<ReviewRecoveryTask> getTasksByOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return List.of();
        }
        return taskRepository.findByOrderIdAndBatchStatusIn(orderId, VISIBLE_BATCH_STATUSES).stream()
                .filter(task -> task.getStatus() != ReviewRecoveryTaskStatus.CANCELLED)
                .toList();
    }

    @Override
    @Transactional
    public ReviewRecoveryTask createTask(Long reviewId, User createdBy) {
        Review review = requireReview(reviewId);
        Order order = requireOrder(review);

        if (hasActiveTask(reviewId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задача восстановления уже создана");
        }

        ReviewRecoveryBatch batch = getOrCreateActiveBatch(order, createdBy);
        LocalDate scheduledDate = nextScheduledDate(batch);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .batch(batch)
                .order(order)
                .sourceReview(review)
                .worker(resolveWorker(order, review))
                .manager(order.getManager())
                .bot(review.getBot())
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .originalText(review.getText())
                .recoveryText(safeText(review.getText()))
                .originalAnswer(review.getAnswer())
                .recoveryAnswer(review.getAnswer())
                .botLoginSnapshot(botLogin(review.getBot()))
                .botPasswordSnapshot(botPassword(review.getBot()))
                .botFioSnapshot(botFio(review.getBot()))
                .scheduledDate(scheduledDate)
                .createdBy(createdBy)
                .build();

        ReviewRecoveryTask savedTask = taskRepository.save(task);
        log.info("Создана задача восстановления {} для отзыва {}, заказа {}, дата {}",
                savedTask.getId(), review.getId(), order.getId(), scheduledDate);
        return savedTask;
    }

    @Override
    @Transactional
    public ReviewRecoveryTask updateTask(Long taskId, String recoveryText, String recoveryAnswer, LocalDate scheduledDate) {
        ReviewRecoveryTask task = requireTask(taskId);
        if (task.getStatus() != ReviewRecoveryTaskStatus.PLANNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Можно редактировать только активную задачу восстановления");
        }

        if (isBlankOrPlaceholder(recoveryText)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заполните текст восстановления");
        }

        task.setRecoveryText(recoveryText.trim());
        if (recoveryAnswer != null) {
            task.setRecoveryAnswer(recoveryAnswer.trim());
        }
        if (scheduledDate != null) {
            task.setScheduledDate(scheduledDate);
        }

        return taskRepository.save(task);
    }

    @Override
    @Transactional
    public ReviewRecoveryTask completeTask(Long taskId, User completedBy) {
        ReviewRecoveryTask task = requireTask(taskId);
        if (task.getStatus() == ReviewRecoveryTaskStatus.DONE) {
            return task;
        }
        if (task.getStatus() == ReviewRecoveryTaskStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отмененную задачу восстановления нельзя выполнить");
        }
        if (isBlankOrPlaceholder(task.getRecoveryText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заполните текст восстановления");
        }

        task.setStatus(ReviewRecoveryTaskStatus.DONE);
        task.setCompletedDate(LocalDate.now());
        task.setCompletedBy(completedBy);
        ReviewRecoveryTask savedTask = taskRepository.save(task);
        completeBatchIfReady(savedTask.getBatch());

        log.info("Задача восстановления {} выполнена, отзыв {}, заказ {}",
                savedTask.getId(), reviewId(savedTask), orderId(savedTask));
        return savedTask;
    }

    @Override
    @Transactional
    public ReviewRecoveryTask cancelTask(Long taskId) {
        ReviewRecoveryTask task = requireTask(taskId);
        if (task.getStatus() == ReviewRecoveryTaskStatus.CANCELLED) {
            return task;
        }
        if (task.getStatus() == ReviewRecoveryTaskStatus.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Выполненную задачу восстановления нельзя удалить");
        }

        task.setStatus(ReviewRecoveryTaskStatus.CANCELLED);
        ReviewRecoveryTask savedTask = taskRepository.save(task);
        completeBatchIfReady(savedTask.getBatch());

        log.info("Задача восстановления {} отменена, отзыв {}, заказ {}",
                savedTask.getId(), reviewId(savedTask), orderId(savedTask));
        return savedTask;
    }

    @Override
    @Transactional
    public ReviewRecoveryTask changeTaskBot(Long taskId) {
        ReviewRecoveryTask task = requireTask(taskId);
        if (task.getStatus() != ReviewRecoveryTaskStatus.PLANNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Аккаунт можно менять только у активной задачи восстановления");
        }

        Bot nextBot = pickReplacementBot(task);
        if (nextBot == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нет доступных аккаунтов для восстановления");
        }

        task.setBot(nextBot);
        task.setBotLoginSnapshot(botLogin(nextBot));
        task.setBotPasswordSnapshot(botPassword(nextBot));
        task.setBotFioSnapshot(botFio(nextBot));
        syncSourceReviewBot(task, nextBot);
        return taskRepository.save(task);
    }

    @Override
    @Transactional
    public ReviewRecoveryTask deactivateAndChangeTaskBot(Long taskId, Long botId) {
        ReviewRecoveryTask task = requireTask(taskId);
        Long currentBotId = botId != null && botId > 0
                ? botId
                : task.getBot() != null ? task.getBot().getId() : null;

        if (currentBotId != null && currentBotId > 0) {
            Bot bot = botService.findBotById(currentBotId);
            if (bot != null) {
                bot.setActive(false);
                botService.save(bot);
            }
        }

        return changeTaskBot(taskId);
    }

    @Override
    @Transactional
    public ReviewRecoveryBatch markClientNotified(Long batchId, User notifiedBy) {
        ReviewRecoveryBatch batch = requireBatch(batchId);
        if (batch.getStatus() != ReviewRecoveryBatchStatus.COMPLETED
                && batch.getStatus() != ReviewRecoveryBatchStatus.CLIENT_NOTIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала завершите все задачи восстановления");
        }

        batch.setStatus(ReviewRecoveryBatchStatus.CLIENT_NOTIFIED);
        batch.setClientNotifiedBy(notifiedBy);
        batch.setClientNotifiedAt(Instant.now());
        ReviewRecoveryBatch savedBatch = batchRepository.save(batch);
        deleteCompletionReminder(savedBatch);
        return savedBatch;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean taskBelongsToOrder(Long taskId, Long orderId) {
        if (taskId == null || taskId <= 0 || orderId == null || orderId <= 0) {
            return false;
        }
        return taskRepository.existsByIdAndOrderId(taskId, orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean batchBelongsToOrder(Long batchId, Long orderId) {
        if (batchId == null || batchId <= 0 || orderId == null || orderId <= 0) {
            return false;
        }
        return batchRepository.existsByIdAndOrderId(batchId, orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewRecoveryTask> getDueTasksToAdmin(LocalDate date, String keyword, Pageable pageable) {
        return taskRepository.findDueTasksToAdmin(
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                safeDate(date),
                keyword(keyword),
                pageable
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewRecoveryTask> getDueTasksToOwner(Collection<com.hunt.otziv.u_users.model.Manager> managers, LocalDate date, String keyword, Pageable pageable) {
        if (managers == null || managers.isEmpty()) {
            return emptyPage(pageable);
        }
        return taskRepository.findDueTasksToOwner(managers, ReviewRecoveryTaskStatus.PLANNED, ReviewRecoveryBatchStatus.OPEN, safeDate(date), keyword(keyword), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewRecoveryTask> getDueTasksToManager(com.hunt.otziv.u_users.model.Manager manager, LocalDate date, String keyword, Pageable pageable) {
        if (manager == null) {
            return emptyPage(pageable);
        }
        return taskRepository.findDueTasksToManager(manager, ReviewRecoveryTaskStatus.PLANNED, ReviewRecoveryBatchStatus.OPEN, safeDate(date), keyword(keyword), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewRecoveryTask> getDueTasksToWorker(Worker worker, LocalDate date, String keyword, Pageable pageable) {
        if (worker == null) {
            return emptyPage(pageable);
        }
        return taskRepository.findDueTasksToWorker(worker, ReviewRecoveryTaskStatus.PLANNED, ReviewRecoveryBatchStatus.OPEN, safeDate(date), keyword(keyword), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public int countDueTasksToAdmin(LocalDate date) {
        return toIntCount(taskRepository.countByStatusAndBatchStatusAndScheduledDateLessThanEqual(
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                safeDate(date)
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public int countDueTasksToOwner(Collection<com.hunt.otziv.u_users.model.Manager> managers, LocalDate date) {
        if (managers == null || managers.isEmpty()) {
            return 0;
        }
        return toIntCount(taskRepository.countByStatusAndBatchStatusAndScheduledDateLessThanEqualAndOrderManagerIn(
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                safeDate(date),
                managers
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public int countDueTasksToManager(com.hunt.otziv.u_users.model.Manager manager, LocalDate date) {
        if (manager == null) {
            return 0;
        }
        return toIntCount(taskRepository.countByStatusAndBatchStatusAndScheduledDateLessThanEqualAndOrderManager(
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                safeDate(date),
                manager
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public int countDueTasksToWorker(Worker worker, LocalDate date) {
        if (worker == null) {
            return 0;
        }
        return toIntCount(taskRepository.countByStatusAndBatchStatusAndScheduledDateLessThanEqualAndWorker(
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                safeDate(date),
                worker
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public int countCompletedBatchesToAdmin() {
        return toIntCount(batchRepository.countByStatus(ReviewRecoveryBatchStatus.COMPLETED));
    }

    @Override
    @Transactional(readOnly = true)
    public int countCompletedBatchesToOwner(Collection<com.hunt.otziv.u_users.model.Manager> managers) {
        if (managers == null || managers.isEmpty()) {
            return 0;
        }
        return toIntCount(batchRepository.countByStatusAndManagerIn(ReviewRecoveryBatchStatus.COMPLETED, managers));
    }

    @Override
    @Transactional(readOnly = true)
    public int countCompletedBatchesToManager(com.hunt.otziv.u_users.model.Manager manager) {
        if (manager == null) {
            return 0;
        }
        return toIntCount(batchRepository.countByStatusAndManager(ReviewRecoveryBatchStatus.COMPLETED, manager));
    }

    @Override
    @Transactional
    public int archiveClientNotifiedBefore(Instant cutoff, Instant archivedAt) {
        if (cutoff == null) {
            return 0;
        }

        Instant resolvedArchivedAt = archivedAt == null ? Instant.now() : archivedAt;
        int archived = batchRepository.archiveClientNotifiedBatches(
                ReviewRecoveryBatchStatus.CLIENT_NOTIFIED,
                ReviewRecoveryBatchStatus.ARCHIVED,
                cutoff,
                resolvedArchivedAt
        );
        if (archived > 0) {
            log.info("Архивировано пачек восстановления после уведомления клиента: {}", archived);
        }
        return archived;
    }

    private boolean hasActiveTask(Long reviewId) {
        return taskRepository.countActiveTasksForReview(reviewId, ACTIVE_TASK_STATUSES, ACTIVE_BATCH_STATUSES) > 0;
    }

    private ReviewRecoveryBatch getOrCreateActiveBatch(Order order, User createdBy) {
        ReviewRecoveryBatch batch = batchRepository
                .findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(order.getId(), ACTIVE_BATCH_STATUSES)
                .orElseGet(() -> ReviewRecoveryBatch.builder()
                        .order(order)
                        .manager(order.getManager())
                        .status(ReviewRecoveryBatchStatus.OPEN)
                        .createdBy(createdBy)
                        .build());

        if (batch.getStatus() == ReviewRecoveryBatchStatus.COMPLETED) {
            batch.setStatus(ReviewRecoveryBatchStatus.OPEN);
            batch.setCompletedAt(null);
        }

        if (batch.getId() == null || batch.getStatus() == ReviewRecoveryBatchStatus.OPEN) {
            return batchRepository.save(batch);
        }

        return batch;
    }

    private LocalDate nextScheduledDate(ReviewRecoveryBatch batch) {
        if (batch == null || batch.getId() == null) {
            return LocalDate.now();
        }

        LocalDate maxDate = taskRepository.maxScheduledDateByBatchId(batch.getId(), ReviewRecoveryTaskStatus.CANCELLED);
        return maxDate == null ? LocalDate.now() : maxDate.plusDays(SCHEDULE_STEP_DAYS);
    }

    private void completeBatchIfReady(ReviewRecoveryBatch batch) {
        if (batch == null || batch.getId() == null) {
            return;
        }

        long plannedCount = taskRepository.countByBatchIdAndStatus(batch.getId(), ReviewRecoveryTaskStatus.PLANNED);
        if (plannedCount > 0) {
            return;
        }
        long doneCount = taskRepository.countByBatchIdAndStatus(batch.getId(), ReviewRecoveryTaskStatus.DONE);
        if (doneCount <= 0) {
            return;
        }

        batch.setStatus(ReviewRecoveryBatchStatus.COMPLETED);
        batch.setCompletedAt(Instant.now());
        batchRepository.save(batch);
        createCompletionReminder(batch);
        log.info("Пачка восстановления {} завершена", batch.getId());
    }

    private void createCompletionReminder(ReviewRecoveryBatch batch) {
        User managerUser = managerUser(batch);
        if (managerUser == null) {
            return;
        }

        personalReminderService.createSystemReminderDueNow(
                managerUser,
                recoveryReminderTitle(batch),
                recoveryReminderText(batch),
                PersonalReminderService.SOURCE_REVIEW_RECOVERY_BATCH,
                batch.getId(),
                batch != null && batch.getOrder() != null ? batch.getOrder().getId() : null
        );
    }

    private void deleteCompletionReminder(ReviewRecoveryBatch batch) {
        User managerUser = managerUser(batch);
        if (managerUser == null) {
            return;
        }

        personalReminderService.deleteSystemReminder(
                managerUser,
                recoveryReminderTitle(batch),
                recoveryReminderText(batch)
        );

        personalReminderService.deleteSystemReminderBySource(
                managerUser,
                PersonalReminderService.SOURCE_REVIEW_RECOVERY_BATCH,
                batch.getId()
        );

        Long orderId = batch != null && batch.getOrder() != null ? batch.getOrder().getId() : null;
        if (orderId != null) {
            personalReminderService.deleteSystemRemindersByTitlePrefixAndTextFragment(
                    managerUser,
                    "Восстановление завершено",
                    "#" + orderId
            );
        }
    }

    private User managerUser(ReviewRecoveryBatch batch) {
        return batch != null && batch.getManager() != null ? batch.getManager().getUser() : null;
    }

    private String recoveryReminderTitle(ReviewRecoveryBatch batch) {
        return limit("Восстановление завершено: " + recoveryCompanyTitle(batch), 120);
    }

    private String recoveryReminderText(ReviewRecoveryBatch batch) {
        Long orderId = batch != null && batch.getOrder() != null ? batch.getOrder().getId() : null;
        String chatUrl = recoveryChatUrl(batch);
        String chatLine = chatUrl.isBlank() ? "Чат: не указан" : "Чат: " + chatUrl;

        return limit(
                "Компания: " + recoveryCompanyTitle(batch)
                        + "\nЗаказ #" + (orderId == null ? "-" : orderId)
                        + "\n" + chatLine
                        + "\nВсе восстановления завершены, можно написать клиенту.",
                1000
        );
    }

    private String recoveryCompanyTitle(ReviewRecoveryBatch batch) {
        Company company = recoveryCompany(batch);
        String title = company != null ? safeText(company.getTitle()).trim() : "";
        return title.isBlank() ? "компания не указана" : title;
    }

    private String recoveryChatUrl(ReviewRecoveryBatch batch) {
        Company company = recoveryCompany(batch);
        return company == null ? "" : safeText(company.getUrlChat()).trim();
    }

    private Company recoveryCompany(ReviewRecoveryBatch batch) {
        return batch != null && batch.getOrder() != null ? batch.getOrder().getCompany() : null;
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }

        if (maxLength <= 1) {
            return text.substring(0, Math.max(maxLength, 0));
        }

        return text.substring(0, maxLength - 1).trim() + "…";
    }

    private Review requireReview(Long reviewId) {
        if (reviewId == null || reviewId <= 0) {
            throw new EntityNotFoundException("Отзыв не найден");
        }

        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new EntityNotFoundException("Отзыв не найден: " + reviewId));
    }

    private ReviewRecoveryTask requireTask(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new EntityNotFoundException("Задача восстановления не найдена");
        }

        return taskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Задача восстановления не найдена: " + taskId));
    }

    private ReviewRecoveryBatch requireBatch(Long batchId) {
        if (batchId == null || batchId <= 0) {
            throw new EntityNotFoundException("Пачка восстановления не найдена");
        }

        return batchRepository.findById(batchId)
                .orElseThrow(() -> new EntityNotFoundException("Пачка восстановления не найдена: " + batchId));
    }

    private Order requireOrder(Review review) {
        OrderDetails details = review != null ? review.getOrderDetails() : null;
        Order order = details != null ? details.getOrder() : null;
        if (order == null || order.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У отзыва нет заказа для восстановления");
        }
        return order;
    }

    private Worker resolveWorker(Order order, Review review) {
        if (review != null && review.getWorker() != null) {
            return review.getWorker();
        }
        return order != null ? order.getWorker() : null;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private Long orderId(ReviewRecoveryTask task) {
        return task != null && task.getOrder() != null ? task.getOrder().getId() : null;
    }

    private Long reviewId(ReviewRecoveryTask task) {
        return task != null && task.getSourceReview() != null ? task.getSourceReview().getId() : null;
    }

    private String botLogin(Bot bot) {
        return bot == null ? null : bot.getLogin();
    }

    private String botPassword(Bot bot) {
        return bot == null ? null : bot.getPassword();
    }

    private String botFio(Bot bot) {
        return bot == null ? null : bot.getFio();
    }

    private Bot pickReplacementBot(ReviewRecoveryTask task) {
        City city = task != null && task.getSourceReview() != null
                && task.getSourceReview().getFilial() != null
                ? task.getSourceReview().getFilial().getCity()
                : null;
        if (city == null || city.getId() == null) {
            return null;
        }

        Long currentBotId = task.getBot() != null ? task.getBot().getId() : null;
        List<Bot> candidates = botService.getFindAllByFilialCityId(city.getId()).stream()
                .filter(bot -> bot != null && bot.isActive() && bot.getId() != null)
                .filter(bot -> !Objects.equals(bot.getId(), currentBotId))
                .toList();

        if (!candidates.isEmpty()) {
            return candidates.get(random.nextInt(candidates.size()));
        }

        return botService.claimReserveBotForCity(city, currentBotId == null ? Set.of() : Set.of(currentBotId))
                .orElse(null);
    }

    private void syncSourceReviewBot(ReviewRecoveryTask task, Bot bot) {
        Review review = task != null ? task.getSourceReview() : null;
        if (review == null || review.getId() == null || bot == null) {
            return;
        }

        review.setBot(bot);
        reviewRepository.save(review);
    }

    private LocalDate safeDate(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }

    private String keyword(String keyword) {
        return "%" + (keyword == null ? "" : keyword.trim().toLowerCase()) + "%";
    }

    private Page<ReviewRecoveryTask> emptyPage(Pageable pageable) {
        return new PageImpl<>(List.of(), pageable, 0);
    }

    private int toIntCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }
}
