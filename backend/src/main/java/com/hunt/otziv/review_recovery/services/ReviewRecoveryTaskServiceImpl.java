package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.archive.dto.ArchiveReviewRecoverySource;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.client_messages.service.ReviewRecoveryNoticeScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.worker_flow.WorkerTaskCompletionMonitorService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.bot.ReviewBotCooldownService;
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
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import jakarta.persistence.EntityNotFoundException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewRecoveryTaskServiceImpl implements ReviewRecoveryTaskService {

    private static final String STATUS_TO_PUBLISH = "Публикация";
    private static final String STATUS_PUBLIC = "Опубликовано";
    private static final String STATUS_TO_PAY = "Выставлен счет";
    private static final String STATUS_REMINDER = "Напоминание";
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
    private final ReviewRecoveryNoticeScheduler recoveryNoticeScheduler;
    private final ReviewRecoveryHoldService recoveryHoldService;
    private final ReviewRecoveryGateService recoveryGateService;
    private final GamificationEventService gamificationEventService;
    private final BusinessAuditService businessAuditService;
    private final ReviewBotCooldownService botCooldownService;
    private final OrderRepository orderRepository;
    private final ManagerRepository managerRepository;
    private final WorkerRepository workerRepository;
    private final OrderStatusCheckerService orderStatusCheckerService;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final ObjectProvider<CommonBillingService> commonBillingServiceProvider;

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
    public ReviewRecoveryTask createArchiveTask(ArchiveReviewRecoverySource source, User createdBy) {
        if (source == null || source.orderId() == null || source.reviewId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Архивный отзыв для восстановления не найден");
        }
        if (hasActiveArchiveTask(source.reviewId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задача восстановления уже создана");
        }

        Manager manager = source.managerId() == null
                ? null
                : managerRepository.findById(source.managerId()).orElse(null);
        Worker worker = source.workerId() == null
                ? null
                : workerRepository.findById(source.workerId()).orElse(null);
        Bot bot = source.botId() == null
                ? null
                : botService.findBotById(source.botId());
        ReviewRecoveryBatch batch = getOrCreateActiveArchiveBatch(source, manager, createdBy);
        LocalDate scheduledDate = nextArchiveScheduledDate(source);

        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .batch(batch)
                .archiveOrderId(source.orderId())
                .archiveReviewId(source.reviewId())
                .archiveCompanyId(source.companyId())
                .archiveOrderDetailsId(source.orderDetailsId())
                .archiveOrderStatus(source.orderStatus())
                .archiveCompanyTitle(source.companyTitle())
                .archiveCompanyNote(source.companyNote())
                .archiveOrderNote(source.orderNote())
                .archiveFilialCity(source.filialCity())
                .archiveFilialCityId(source.filialCityId())
                .archiveFilialTitle(source.filialTitle())
                .archiveFilialUrl(source.filialUrl())
                .archiveCategory(source.category())
                .archiveSubCategory(source.subCategory())
                .archiveProductId(source.productId())
                .archiveProductTitle(source.productTitle())
                .archiveReviewCreated(source.created())
                .archiveReviewChanged(source.changed())
                .archiveReviewPublishedDate(source.publishedDate())
                .archiveReviewPublish(source.publish())
                .archiveReviewVigul(source.vigul())
                .archiveReviewPrice(source.price())
                .archiveReviewUrl(source.url())
                .worker(worker)
                .manager(manager)
                .bot(bot)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .originalText(source.text())
                .recoveryText(safeText(source.text()))
                .originalAnswer(source.answer())
                .recoveryAnswer(source.answer())
                .botLoginSnapshot(firstNonBlank(botLogin(bot), source.botLogin()))
                .botPasswordSnapshot(firstNonBlank(botPassword(bot), source.botPassword()))
                .botFioSnapshot(firstNonBlank(botFio(bot), source.botFio()))
                .scheduledDate(scheduledDate)
                .createdBy(createdBy)
                .build();

        ReviewRecoveryTask savedTask = taskRepository.save(task);
        log.info("Создана архивная задача восстановления {} для архивного отзыва {}, заказа {}, дата {}",
                savedTask.getId(), source.reviewId(), source.orderId(), scheduledDate);
        return savedTask;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewRecoveryTask getTask(Long taskId) {
        return requireTask(taskId);
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
        gamificationEventService.recordReviewRecoveryTaskDone(savedTask);
        completeBatchIfReady(savedTask.getBatch());
        resumeOrderAfterRecoveryIfReady(savedTask.getOrder());
        auditTaskCompleted(savedTask);

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
        resumeOrderAfterRecoveryIfReady(savedTask.getOrder());

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

        Bot oldBot = task.getBot();
        Bot nextBot = pickReplacementBot(task);
        if (nextBot == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нет доступных аккаунтов для восстановления");
        }

        markReleasedIfChanged(oldBot, nextBot, "review recovery task bot changed");
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
                boolean oldActive = bot.isActive();
                bot.setActive(false);
                auditActiveChange(bot, oldActive, false, "review recovery task block button");
                botService.save(bot);
            }
        }

        return changeTaskBot(taskId);
    }

    @Override
    @Transactional
    public ReviewRecoveryBatch markClientNotified(Long batchId, User notifiedBy) {
        return markClientNotifiedInternal(batchId, notifiedBy);
    }

    @Override
    @Transactional
    public ReviewRecoveryBatch markClientNotifiedAutomatically(Long batchId) {
        return markClientNotifiedInternal(batchId, null);
    }

    private ReviewRecoveryBatch markClientNotifiedInternal(Long batchId, User notifiedBy) {
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
        recoveryHoldService.releaseDeadlineHold(savedBatch);
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

    private boolean hasActiveArchiveTask(Long archiveReviewId) {
        return taskRepository.countActiveTasksForArchiveReview(archiveReviewId, ACTIVE_TASK_STATUSES, ACTIVE_BATCH_STATUSES) > 0;
    }

    private ReviewRecoveryBatch getOrCreateActiveBatch(Order order, User createdBy) {
        ReviewRecoveryBatch batch = batchRepository
                .findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(order.getId(), ACTIVE_BATCH_STATUSES)
                .orElseGet(() -> ReviewRecoveryBatch.builder()
                        .order(order)
                        .manager(order.getManager())
                        .status(ReviewRecoveryBatchStatus.OPEN)
                        .createdBy(createdBy)
                        .holdStartedAt(Instant.now())
                        .build());

        if (batch.getStatus() == ReviewRecoveryBatchStatus.COMPLETED) {
            deleteCompletionReminder(batch);
            batch.setStatus(ReviewRecoveryBatchStatus.OPEN);
            batch.setCompletedAt(null);
            batch.setClientNotifiedAt(null);
            batch.setClientNotifiedBy(null);
            batch.setHoldStartedAt(Instant.now());
            batch.setHoldReleasedAt(null);
            batch.setDeadlineShiftAppliedAt(null);
            batch.setDeadlineShiftSeconds(0);
        }

        if (batch.getHoldStartedAt() == null || batch.getHoldReleasedAt() != null) {
            batch.setHoldStartedAt(Instant.now());
            batch.setHoldReleasedAt(null);
            batch.setDeadlineShiftAppliedAt(null);
            batch.setDeadlineShiftSeconds(0);
        }

        if (batch.getId() == null || batch.getStatus() == ReviewRecoveryBatchStatus.OPEN) {
            return batchRepository.save(batch);
        }

        return batch;
    }

    private ReviewRecoveryBatch getOrCreateActiveArchiveBatch(
            ArchiveReviewRecoverySource source,
            Manager manager,
            User createdBy
    ) {
        ReviewRecoveryBatch batch = batchRepository
                .findFirstByArchiveOrderIdAndStatusInOrderByCreatedAtDesc(source.orderId(), ACTIVE_BATCH_STATUSES)
                .orElseGet(() -> ReviewRecoveryBatch.builder()
                        .archiveOrderId(source.orderId())
                        .archiveCompanyTitle(source.companyTitle())
                        .archiveChatUrl(source.companyChatUrl())
                        .archiveOrderStatus(source.orderStatus())
                        .manager(manager)
                        .status(ReviewRecoveryBatchStatus.OPEN)
                        .createdBy(createdBy)
                        .holdStartedAt(Instant.now())
                        .build());

        if (batch.getStatus() == ReviewRecoveryBatchStatus.COMPLETED) {
            batch.setStatus(ReviewRecoveryBatchStatus.OPEN);
            batch.setCompletedAt(null);
            batch.setClientNotifiedAt(null);
            batch.setClientNotifiedBy(null);
            batch.setHoldStartedAt(Instant.now());
            batch.setHoldReleasedAt(null);
            batch.setDeadlineShiftAppliedAt(null);
            batch.setDeadlineShiftSeconds(0);
        }

        batch.setArchiveOrderId(source.orderId());
        batch.setArchiveCompanyTitle(source.companyTitle());
        batch.setArchiveChatUrl(source.companyChatUrl());
        batch.setArchiveOrderStatus(source.orderStatus());
        if (batch.getManager() == null) {
            batch.setManager(manager);
        }
        if (batch.getHoldStartedAt() == null || batch.getHoldReleasedAt() != null) {
            batch.setHoldStartedAt(Instant.now());
            batch.setHoldReleasedAt(null);
            batch.setDeadlineShiftAppliedAt(null);
            batch.setDeadlineShiftSeconds(0);
        }

        return batchRepository.save(batch);
    }

    private LocalDate nextScheduledDate(ReviewRecoveryBatch batch) {
        Long orderId = batch != null && batch.getOrder() != null ? batch.getOrder().getId() : null;
        return recoveryGateService.nextScheduledDate(orderId);
    }

    private LocalDate nextArchiveScheduledDate(ArchiveReviewRecoverySource source) {
        LocalDate archiveReviewDate = maxDate(
                maxDate(source == null ? null : source.publishedDate(), source == null ? null : source.changed()),
                source == null ? null : source.created()
        );
        LocalDate baseDate = maxDate(
                archiveReviewDate,
                source == null || source.orderId() == null
                        ? null
                        : taskRepository.maxScheduledDateByArchiveOrderId(source.orderId(), ReviewRecoveryTaskStatus.CANCELLED)
        );
        return maxDate(baseDate, LocalDate.now()).plusDays(ReviewRecoveryGateService.RECOVERY_SCHEDULE_STEP_DAYS);
    }

    private LocalDate maxDate(LocalDate first, LocalDate second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private ReviewRecoveryBatch completeBatchIfReady(ReviewRecoveryBatch batch) {
        if (batch == null || batch.getId() == null) {
            return null;
        }

        long plannedCount = taskRepository.countByBatchIdAndStatus(batch.getId(), ReviewRecoveryTaskStatus.PLANNED);
        if (plannedCount > 0) {
            return null;
        }
        long doneCount = taskRepository.countByBatchIdAndStatus(batch.getId(), ReviewRecoveryTaskStatus.DONE);
        if (doneCount <= 0) {
            return null;
        }

        batch.setStatus(ReviewRecoveryBatchStatus.COMPLETED);
        batch.setCompletedAt(Instant.now());
        ReviewRecoveryBatch savedBatch = batchRepository.save(batch);
        if (isArchiveOnlyBatch(savedBatch)) {
            savedBatch.setStatus(ReviewRecoveryBatchStatus.CLIENT_NOTIFIED);
            savedBatch.setClientNotifiedAt(Instant.now());
            return batchRepository.save(savedBatch);
        }
        createCompletionReminder(savedBatch);
        handleCompletedBatchClientNotice(savedBatch);
        log.info("Пачка восстановления {} завершена", batch.getId());
        return savedBatch;
    }

    private boolean isArchiveOnlyBatch(ReviewRecoveryBatch batch) {
        return batch != null && batch.getOrder() == null && batch.getArchiveOrderId() != null;
    }

    private void resumeOrderAfterRecoveryIfReady(Order sourceOrder) {
        if (sourceOrder == null || sourceOrder.getId() == null) {
            return;
        }
        Long orderId = sourceOrder.getId();
        if (recoveryGateService.hasActiveRecoveryTasks(orderId)) {
            return;
        }

        Order order = orderRepository.findByIdForMutation(orderId).orElse(sourceOrder);
        if (order == null || order.getId() == null) {
            return;
        }

        order.setStatusChangedAt(LocalDateTime.now());
        orderRepository.save(order);

        String status = statusTitle(order);
        try {
            if (STATUS_TO_PUBLISH.equals(status)) {
                orderStatusCheckerService.checkAndMarkOrderCompleted(order);
                return;
            }
            if (STATUS_PUBLIC.equals(status)) {
                CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
                if (commonBillingService != null && commonBillingService.refreshLinkedOrderAmount(order.getId())) {
                    return;
                }
                paymentInvoiceRetryScheduler.scheduleInitialInvoice(order);
                return;
            }
            if (STATUS_TO_PAY.equals(status) || STATUS_REMINDER.equals(status)) {
                CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
                if (commonBillingService != null) {
                    commonBillingService.refreshLinkedOrderAmount(order.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Не удалось возобновить клиентский сценарий после восстановления, orderId={}", order.getId(), e);
        }
    }

    private String statusTitle(Order order) {
        return order == null || order.getStatus() == null || order.getStatus().getTitle() == null
                ? ""
                : order.getStatus().getTitle();
    }

    private void handleCompletedBatchClientNotice(ReviewRecoveryBatch batch) {
        if (batch == null || batch.getOrder() == null) {
            return;
        }
        if (recoveryHoldService.shouldSkipClientRecoveryNotice(batch.getOrder())) {
            batch.setStatus(ReviewRecoveryBatchStatus.CLIENT_NOTIFIED);
            batch.setClientNotifiedAt(Instant.now());
            ReviewRecoveryBatch savedBatch = batchRepository.save(batch);
            recoveryHoldService.releaseDeadlineHold(savedBatch);
            return;
        }
        boolean scheduled = recoveryNoticeScheduler.scheduleNotice(batch);
        if (!scheduled) {
            recoveryHoldService.releaseWithoutClientNotice(batch);
        }
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
        if (batch != null && batch.getOrder() == null && batch.getArchiveOrderId() != null) {
            String title = safeText(batch.getArchiveCompanyTitle()).trim();
            return title.isBlank() ? "архивная компания не указана" : title;
        }
        Company company = recoveryCompany(batch);
        String title = company != null ? safeText(company.getTitle()).trim() : "";
        return title.isBlank() ? "компания не указана" : title;
    }

    private String recoveryChatUrl(ReviewRecoveryBatch batch) {
        if (batch != null && batch.getOrder() == null && batch.getArchiveOrderId() != null) {
            return safeText(batch.getArchiveChatUrl()).trim();
        }
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Long orderId(ReviewRecoveryTask task) {
        if (task == null) {
            return null;
        }
        return task.getOrder() != null ? task.getOrder().getId() : task.getArchiveOrderId();
    }

    private Long reviewId(ReviewRecoveryTask task) {
        if (task == null) {
            return null;
        }
        return task.getSourceReview() != null ? task.getSourceReview().getId() : task.getArchiveReviewId();
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
        if ((city == null || city.getId() == null) && task != null && task.getArchiveFilialCityId() != null) {
            city = City.builder()
                    .id(task.getArchiveFilialCityId())
                    .title(task.getArchiveFilialCity())
                    .build();
        }
        if (city == null || city.getId() == null) {
            return null;
        }

        Long currentBotId = task.getBot() != null ? task.getBot().getId() : null;
        List<Bot> candidates = botService.getFindAllByFilialCityId(city.getId()).stream()
                .filter(bot -> bot != null && bot.isActive() && bot.getId() != null)
                .filter(botCooldownService::isAvailableForAssignment)
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

        markReleasedIfChanged(review.getBot(), bot, "review recovery source review bot changed");
        review.setBot(bot);
        reviewRepository.save(review);
    }

    private void markReleasedIfChanged(Bot oldBot, Bot newBot, String reason) {
        Long oldBotId = oldBot != null ? oldBot.getId() : null;
        Long newBotId = newBot != null ? newBot.getId() : null;
        if (oldBotId != null && !Objects.equals(oldBotId, newBotId)) {
            botCooldownService.markReleased(oldBot, reason);
        }
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

    private void auditActiveChange(Bot bot, boolean oldActive, boolean newActive, String details) {
        if (oldActive == newActive || bot == null || bot.getId() == null) {
            return;
        }

        businessAuditService.recordSafely(
                "bot_active_changed",
                "bot",
                bot.getId(),
                null,
                null,
                oldActive,
                newActive,
                details
        );
    }

    private void auditTaskCompleted(ReviewRecoveryTask task) {
        if (task == null || task.getId() == null) {
            return;
        }

        businessAuditService.recordSafely(
                WorkerTaskCompletionMonitorService.ACTION_TASK_COMPLETED,
                "review_recovery_task",
                task.getId(),
                orderId(task),
                reviewId(task),
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryTaskStatus.DONE,
                "Задача восстановления отмечена выполненной"
        );
    }
}
