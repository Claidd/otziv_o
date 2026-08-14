package com.hunt.otziv.bad_reviews.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageAttempt;
import com.hunt.otziv.client_messages.model.ScheduledMessageAttemptStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageAttemptRepository;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.common_billing.service.CommonPayableChangeDisposition;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentBusinessClock;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.worker_flow.service.WorkerTaskCompletionMonitorService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.bot.service.ReviewBotCooldownService;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.bot.service.ReviewBotAssignmentGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class BadReviewTaskServiceImpl implements BadReviewTaskService {

    private static final int DEFAULT_ORIGINAL_RATING = 5;
    private static final int DEFAULT_TARGET_RATING = 2;
    private static final int SCHEDULE_STEP_DAYS = 2;
    private static final int BAD_REVIEW_PREFERRED_COUNTER = 5;
    private static final String STATUS_NOT_PAID = "Не оплачено";
    private static final String BOT_BINDING_CONFLICT =
            "Указанный аккаунт больше не привязан к этой карточке. Обновите данные и повторите действие";
    private static final String INVALID_BOT_ID = "Идентификатор аккаунта не может быть отрицательным";

    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ReviewRepository reviewRepository;
    private final BotService botService;
    private final PersonalReminderService personalReminderService;
    private final AppSettingService appSettingService;
    private final ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;
    private final ObjectProvider<CommonBillingService> commonBillingServiceProvider;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final BadReviewCompletionPostActionOrchestrator completionPostActionOrchestrator;
    private final ScheduledClientMessageAttemptRepository clientMessageAttemptRepository;
    private final GamificationEventService gamificationEventService;
    private final BusinessAuditService businessAuditService;
    private final ReviewBotCooldownService botCooldownService;
    private final ReviewBotAssignmentGuardService assignmentGuardService;
    private final ReviewAccountWalkScheduleService accountWalkScheduleService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;
    private final OrderRepository orderRepository;
    private final ContractorCompletionRewardService contractorCompletionRewardService;
    private final ContractorPaymentBusinessClock contractorPaymentBusinessClock;
    private final BadReviewTaskTransactionRunner transactionRunner;
    private final SecureRandom random = new SecureRandom();

    @Override
    @Transactional
    public int createTasksForUnpaidOrder(Order order) {
        if (order == null || order.getId() == null) {
            return 0;
        }

        List<Review> publishedReviews = reviewRepository.getAllByOrderId(order.getId()).stream()
                .filter(Objects::nonNull)
                .filter(Review::isPublish)
                .toList();

        int created = 0;
        LocalDate startDate = contractorPaymentBusinessClock.today();
        for (Review review : publishedReviews) {
            if (review.getId() == null || hasActiveOrDoneTask(order.getId(), review.getId())) {
                continue;
            }

            Bot reviewBot = review.getBot();
            BadReviewTask task = BadReviewTask.builder()
                    .order(order)
                    .sourceReview(review)
                    .worker(resolveWorker(order, review))
                    .bot(reviewBot)
                    .taskText(reviewText(review))
                    .botLoginSnapshot(botLogin(reviewBot))
                    .botPasswordSnapshot(botPassword(reviewBot))
                    .botFioSnapshot(botFio(reviewBot))
                    .status(BadReviewTaskStatus.NEW)
                    .originalRating(DEFAULT_ORIGINAL_RATING)
                    .targetRating(DEFAULT_TARGET_RATING)
                    .price(validatedTaskPrice(order, review))
                    .scheduledDate(startDate.plusDays((long) created * SCHEDULE_STEP_DAYS))
                    .build();
            badReviewTaskRepository.save(task);
            created++;
        }

        if (created > 0) {
            log.info("Создано плохих задач для заказа {}: {}", order.getId(), created);
        }
        return created;
    }

    @Override
    @Transactional
    public void cancelPendingTasksForOrder(Order order) {
        if (order == null || order.getId() == null) {
            return;
        }

        List<BadReviewTask> tasks = badReviewTaskRepository.findAllByOrderIdAndStatus(order.getId(), BadReviewTaskStatus.NEW);
        if (tasks.isEmpty()) {
            log.info("Ожидающих плохих задач для отмены нет, заказ {}", order.getId());
            return;
        }

        for (BadReviewTask task : tasks) {
            task.setStatus(BadReviewTaskStatus.CANCELED);
        }
        badReviewTaskRepository.saveAll(tasks);
        log.info("Отменено ожидающих плохих задач для заказа {}: {}", order.getId(), tasks.size());
    }

    @Override
    @Transactional
    public int deletePendingTasksForOrder(Order order) {
        Long orderId = order == null ? null : order.getId();
        if (orderId == null) {
            return 0;
        }
        int deleted = badReviewTaskRepository.deleteAllByOrderIdAndStatus(orderId, BadReviewTaskStatus.NEW);
        if (deleted > 0) {
            log.info("Удалено ожидающих плохих задач заказа {}: {}", orderId, deleted);
        }
        return deleted;
    }

    @Override
    @Transactional
    public int reassignPendingTasksForOrder(Long orderId, Worker worker) {
        if (orderId == null || worker == null) {
            return 0;
        }
        int updated = badReviewTaskRepository.reassignWorkerByOrderIdAndStatus(orderId, BadReviewTaskStatus.NEW, worker);
        if (updated > 0) {
            log.info("Перекинули ожидающие плохие задачи заказа {} на работника {}: {}",
                    orderId, worker.getId(), updated);
        }
        return updated;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BadReviewTask completeTask(Long taskId) {
        if (taskStatus(taskId) != BadReviewTaskStatus.NEW) {
            return transactionRunner.required(() -> requireTask(taskId));
        }

        // Provider observation opens its own transaction. It must happen before
        // the task transaction acquires the canonical Order lock; otherwise the
        // observation waits on the lock held by this same request until MySQL's
        // lock timeout expires.
        Long orderId = observeTaskPayableChange(taskId);
        return transactionRunner.required(() -> completeTaskLocked(taskId, orderId));
    }

    private BadReviewTask completeTaskLocked(Long taskId, Long expectedOrderId) {
        PreparedTaskPayableChange preparedChange = prepareTaskPayableChangeLocked(
                taskId,
                expectedOrderId,
                "Выполненная дополнительная задача изменила сумму счета"
        );
        Long orderId = preparedChange.orderId();
        BadReviewTask task = requireTask(taskId);
        if (task.getStatus() != BadReviewTaskStatus.NEW) {
            return task;
        }

        task.setStatus(BadReviewTaskStatus.DONE);
        task.setCompletedDate(contractorPaymentBusinessClock.today());
        releaseCrossCityBotAfterCompletion(task);
        BadReviewTask savedTask = badReviewTaskRepository.save(task);
        contractorCompletionRewardService.ensureCompletedBadReviewTask(savedTask);
        boolean linkedToCommonInvoice = applyLinkedCommonInvoiceChange(
                preparedChange,
                savedTask.getId()
        );
        gamificationEventService.recordBadReviewTaskDone(savedTask);
        if (!linkedToCommonInvoice) {
            // Durable retry state is committed atomically with DONE. The
            // immediate post-commit delivery cancels it after a successful send.
            paymentInvoiceRetryScheduler.scheduleBadReviewInvoiceRetry(savedTask.getOrder());
        }
        runCompletionSideEffectsAfterCommit(savedTask, linkedToCommonInvoice);
        auditTaskCompleted(savedTask);
        log.info("Плохая задача {} выполнена, заказ {}, доплата {}",
                savedTask.getId(),
                savedTask.getOrder() != null ? savedTask.getOrder().getId() : null,
                savedTask.getPrice());
        return savedTask;
    }

    @Override
    public BadReviewTask getTask(Long taskId) {
        return requireTask(taskId);
    }

    private void runCompletionSideEffects(BadReviewTask savedTask, boolean linkedToCommonInvoice) {
        Long orderId = savedTask.getOrder() != null ? savedTask.getOrder().getId() : null;
        try {
            BadReviewTaskSummary summary = orderId == null ? BadReviewTaskSummary.empty() : getSummaryForOrder(orderId);
            expireStalePaymentLinks(savedTask.getOrder());
            sendBadReviewInvoiceIfEnabled(savedTask, summary, linkedToCommonInvoice);
            createTaskCompletionReminder(savedTask, summary);
            createOrderReadyReminderIfNeeded(savedTask.getOrder(), summary);
        } catch (RuntimeException e) {
            log.warn("Плохая задача {} уже отмечена выполненной, но пост-действия не завершились. orderId={}",
                    savedTask.getId(), orderId, e);
        }
    }

    private void sendBadReviewInvoiceIfEnabled(
            BadReviewTask task,
            BadReviewTaskSummary summary,
            boolean linkedToCommonInvoice
    ) {
        Order order = task != null ? task.getOrder() : null;
        if (order == null || order.getId() == null) {
            log.warn("Счет после плохого отзыва не отправлен: заказ не найден, taskId={}", task == null ? null : task.getId());
            recordBadReviewInvoiceAttempt(task, null, ScheduledMessageAttemptStatus.FAILED, null, "order_missing",
                    "Заказ для счета после плохого отзыва не найден", null, 0);
            return;
        }
        if (isBadReviewFinalInvoice(summary)) {
            paymentInvoiceRetryScheduler.cancelBadReviewAutoBan(order, "Финальный счет после плохих пересчитывается");
        }
        if (linkedToCommonInvoice) {
            paymentInvoiceRetryScheduler.cancelBadReviewInvoiceRetry(
                    order,
                    "Заказ обслуживается общим платежным циклом"
            );
            log.info("Счет после плохого отзыва не отправлен отдельно: заказ {} входит в общий счет", order.getId());
            recordBadReviewInvoiceAttempt(task, order, ScheduledMessageAttemptStatus.SKIPPED, null, "common_billing_linked",
                    "Заказ входит в общий счет; сумма общего счета пересчитана", safeBadReviewInvoicePreview(order, summary), 0);
            return;
        }
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true)) {
            log.info("Счет после плохого отзыва пропущен настройкой, orderId={}, taskId={}", order.getId(), task.getId());
            recordBadReviewInvoiceAttempt(task, order, ScheduledMessageAttemptStatus.SKIPPED, null, "bad_review_invoice_disabled",
                    "Отправка счета после плохого отзыва выключена настройкой", safeBadReviewInvoicePreview(order, summary), 0);
            return;
        }
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)) {
            log.info("Счет после плохого отзыва пропущен: моментальные клиентские сообщения выключены, orderId={}, taskId={}",
                    order.getId(), task.getId());
            recordBadReviewInvoiceAttempt(task, order, ScheduledMessageAttemptStatus.SKIPPED, null, "immediate_messages_disabled",
                    "Моментальные клиентские сообщения выключены", safeBadReviewInvoicePreview(order, summary), 0);
            return;
        }
        completionPostActionOrchestrator.deliverInvoice(task.getId(), order.getId());
    }

    private boolean isBadReviewFinalInvoice(BadReviewTaskSummary summary) {
        return summary != null && summary.pending() == 0 && summary.done() > 0;
    }

    private void recordBadReviewInvoiceAttempt(
            BadReviewTask task,
            Order order,
            ScheduledMessageAttemptStatus status,
            String channel,
            String errorCode,
            String errorMessage,
            String message,
            long durationMs
    ) {
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_MONITOR_ENABLED, false)) {
            return;
        }
        Long taskId = task == null ? null : task.getId();
        Long orderId = order == null ? null : order.getId();
        clientMessageAttemptRepository.save(ScheduledClientMessageAttempt.builder()
                .stateId(null)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(badReviewInvoiceTargetKey(order, taskId))
                .companyId(order == null || order.getCompany() == null ? null : order.getCompany().getId())
                .orderId(orderId)
                .archiveOrderId(null)
                .status(status)
                .channel(channel)
                .errorCode(errorCode)
                .errorMessage(limit(errorMessage, 1000))
                .messagePreview(limit(message, 500))
                .durationMs(durationMs)
                .build());
    }

    private String badReviewInvoiceTargetKey(Order order, Long taskId) {
        if (order != null && order.getId() != null) {
            return "bad-review-invoice:order:" + order.getId();
        }
        return "bad-review-invoice:" + (taskId == null ? "unknown" : taskId);
    }

    @Override
    public String buildBadReviewInvoiceMessage(Order order) {
        Long orderId = order == null ? null : order.getId();
        BadReviewTaskSummary summary = orderId == null ? BadReviewTaskSummary.empty() : getSummaryForOrder(orderId);
        return badReviewInvoiceMessage(order, summary);
    }

    private String badReviewInvoiceMessage(Order order, BadReviewTaskSummary summary) {
        if (usesTbankPaymentInstructionSource()) {
            return paymentLinkServiceProvider.getObject().createForOrder(order.getId()).copyText();
        }
        String heading = orderHeading(order);
        String paymentText = paymentInstruction(order) + "\n\nК оплате: " + money(payableSum(order, summary)) + " руб.";
        return heading.isBlank() ? paymentText : heading + "\n\n" + paymentText;
    }

    private String badReviewInvoicePreview(Order order, BadReviewTaskSummary summary) {
        return (orderHeading(order) + " " + managerPayText(order) + " К оплате: " + money(payableSum(order, summary)) + " руб.")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safeBadReviewInvoicePreview(Order order, BadReviewTaskSummary summary) {
        try {
            return badReviewInvoicePreview(order, summary);
        } catch (RuntimeException e) {
            log.warn("Превью счета после плохого отзыва не собрано: orderId={}, reason={}",
                    order == null ? null : order.getId(), readableException(e));
            return null;
        }
    }

    private String paymentInstruction(Order order) {
        if (!usesTbankPaymentInstructionSource()) {
            return managerPayText(order);
        }
        ManagerPaymentLinkResponse link = paymentLinkServiceProvider.getObject().createForOrder(order.getId());
        return link.instructionText();
    }

    private void expireStalePaymentLinks(Order order) {
        Long orderId = order == null ? null : order.getId();
        if (orderId == null) {
            return;
        }
        try {
            PaymentLinkService paymentLinkService = paymentLinkServiceProvider.getIfAvailable();
            if (paymentLinkService == null) {
                return;
            }
            int expired = paymentLinkService.expireStaleLinksForOrder(orderId);
            if (expired > 0) {
                log.info("Протухли устаревшие платежные ссылки после изменения плохих задач: orderId={}, count={}",
                        orderId, expired);
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось протухлить платежные ссылки после изменения плохих задач: orderId={}", orderId, e);
        }
    }

    private boolean usesTbankPaymentInstructionSource() {
        String source = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                "MANAGER_TEXT"
        );
        return "TBANK_LINK".equals((source == null ? "" : source.trim()).toUpperCase(Locale.ROOT));
    }

    private String managerPayText(Order order) {
        String payText = order != null && order.getManager() != null ? order.getManager().getPayText() : null;
        return payText == null || payText.trim().isEmpty()
                ? "Здравствуйте, ваш заказ выполнен, просьба оплатить. Пришлите чек, пожалуйста, как оплатите."
                : payText.trim();
    }

    private String orderHeading(Order order) {
        if (order == null) {
            return "";
        }
        String company = companyTitle(order);
        String filial = order.getFilial() != null && order.getFilial().getTitle() != null
                ? order.getFilial().getTitle().trim()
                : "";
        return filial.isBlank() ? company : company + " - " + filial;
    }

    private String statusTitle(Order order) {
        String title = order != null && order.getStatus() != null && order.getStatus().getTitle() != null
                ? order.getStatus().getTitle().trim()
                : "";
        return title.isBlank() ? STATUS_NOT_PAID : title;
    }

    private String readableException(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message.trim();
    }

    @Override
    @Transactional
    public BadReviewTask updateTask(Long taskId, String taskText, LocalDate scheduledDate) {
        BadReviewTask task = requireTask(taskId);
        if (task.getStatus() != BadReviewTaskStatus.NEW) {
            throw new IllegalStateException("Плохую задачу можно менять только пока она активна");
        }
        if (taskText == null || taskText.isBlank()) {
            throw new IllegalStateException("Текст плохой задачи не указан");
        }
        if (scheduledDate == null) {
            throw new IllegalStateException("Дата плохой задачи не указана");
        }

        task.setTaskText(taskText.trim());
        task.setScheduledDate(scheduledDate);
        return badReviewTaskRepository.save(task);
    }

    @Override
    @Transactional
    public BadReviewTask reassignTask(Long taskId, Worker worker) {
        BadReviewTask task = requireTask(taskId);
        if (task.getStatus() != BadReviewTaskStatus.NEW) {
            throw new IllegalStateException("Специалиста можно менять только у активной плохой задачи");
        }
        if (worker == null || worker.getId() == null) {
            throw new IllegalStateException("Специалист не указан");
        }

        task.setWorker(worker);
        BadReviewTask savedTask = badReviewTaskRepository.save(task);
        log.info("Плохая задача {} назначена специалисту {}", taskId, worker.getId());
        return savedTask;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BadReviewTask cancelTask(Long taskId) {
        BadReviewTaskStatus snapshotStatus = taskStatus(taskId);
        if (snapshotStatus == BadReviewTaskStatus.CANCELED) {
            return transactionRunner.required(() -> requireTask(taskId));
        }
        Long observedOrderId = snapshotStatus == BadReviewTaskStatus.DONE
                ? observeTaskPayableChange(taskId)
                : null;
        return transactionRunner.required(() -> cancelTaskLocked(taskId, snapshotStatus, observedOrderId));
    }

    private void runCompletionSideEffectsAfterCommit(BadReviewTask savedTask, boolean linkedToCommonInvoice) {
        Long taskId = savedTask == null ? null : savedTask.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runCompletionSideEffectsById(taskId, linkedToCommonInvoice);
                }
            });
            return;
        }
        // Unit tests and non-transactional maintenance callers retain the same
        // observable behaviour.
        runCompletionSideEffects(savedTask, linkedToCommonInvoice);
    }

    private void runCompletionSideEffectsById(Long taskId, boolean linkedToCommonInvoice) {
        try {
            runCompletionSideEffects(requireTask(taskId), linkedToCommonInvoice);
        } catch (RuntimeException e) {
            log.warn("Пост-действия выполненной дополнительной задачи не запущены: taskId={}, reason={}",
                    taskId, readableException(e), e);
        }
    }

    private BadReviewTask cancelTaskLocked(
            Long taskId,
            BadReviewTaskStatus expectedStatus,
            Long observedOrderId
    ) {
        BadReviewTask task = requireTask(taskId);
        if (task.getStatus() == BadReviewTaskStatus.CANCELED) {
            return task;
        }
        if (task.getStatus() != expectedStatus) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Статус дополнительной задачи изменился. Обновите страницу и повторите действие"
            );
        }
        PreparedTaskPayableChange preparedChange = task.getStatus() == BadReviewTaskStatus.DONE
                ? prepareTaskPayableChangeLocked(
                        taskId,
                        observedOrderId,
                        "Отмена выполненной дополнительной задачи изменила сумму счета"
                )
                : null;
        Long preparedOrderId = preparedChange == null ? null : preparedChange.orderId();
        if (isPaid(task)) {
            throw new IllegalStateException("После оплаты заказа отмена плохих задач не пересчитывает чек и начисления");
        }

        boolean wasNew = task.getStatus() == BadReviewTaskStatus.NEW;
        task.setStatus(BadReviewTaskStatus.CANCELED);
        if (wasNew) {
            releaseCrossCityBotAfterCompletion(task);
        }
        BadReviewTask savedTask = badReviewTaskRepository.save(task);
        if (!wasNew) {
            Long orderId = savedTask.getOrder() == null ? preparedOrderId : savedTask.getOrder().getId();
            contractorCompletionRewardService.adjustCanceledBadReviewTaskAccrual(orderId, savedTask.getId());
            applyLinkedCommonInvoiceChange(preparedChange, savedTask.getId());
            auditTaskCanceled(savedTask);
        }
        Order order = savedTask.getOrder();
        User managerUser = managerUser(order);
        if (managerUser != null && savedTask.getId() != null) {
            personalReminderService.deleteSystemReminderBySource(
                    managerUser,
                    PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                    savedTask.getId()
            );
        }

        Long orderId = order != null ? order.getId() : null;
        if (orderId != null) {
            BadReviewTaskSummary summary = getSummaryForOrder(orderId);
            expireStalePaymentLinks(order);
            paymentInvoiceRetryScheduler.cancelBadReviewInvoiceRetry(order, "Плохая задача убрана из счета вручную");
            paymentInvoiceRetryScheduler.cancelBadReviewAutoBan(order, "Плохая задача убрана из счета вручную");
            if (summary.pending() == 0 && summary.done() > 0) {
                createOrderReadyReminderIfNeeded(order, summary);
            } else {
                deleteOrderReadyReminder(order);
            }
        }
        return savedTask;
    }

    private Long observeTaskPayableChange(Long taskId) {
        validateTaskIdAndAccess(taskId);
        Long orderId = badReviewTaskRepository.findOrderIdById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Заказ дополнительной задачи не найден"));

        PaymentLinkService paymentLinkService = paymentLinkServiceProvider.getIfAvailable();
        if (paymentLinkService != null) {
            paymentLinkService.reconcileActiveLinkForOrder(orderId);
        }
        return orderId;
    }

    private PreparedTaskPayableChange prepareTaskPayableChangeLocked(
            Long taskId,
            Long expectedOrderId,
            String reason
    ) {
        validateTaskIdAndAccess(taskId);
        Long orderId = badReviewTaskRepository.findOrderIdById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Заказ дополнительной задачи не найден"));
        if (!Objects.equals(orderId, expectedOrderId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Заказ дополнительной задачи изменился. Обновите страницу и повторите действие"
            );
        }

        CommonPayableChangeDisposition commonDisposition = CommonPayableChangeDisposition.NOT_LINKED;
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        if (commonBillingService != null) {
            // lockedInvoice() acquires every member Order in sorted order before
            // the invoice, matching the common-billing routing prelude.
            commonDisposition = commonBillingService.prepareLinkedOrderPayableChange(orderId);
            if (commonDisposition == null) {
                commonDisposition = CommonPayableChangeDisposition.NOT_LINKED;
            }
        }
        orderRepository.findByIdForCounterUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Заказ дополнительной задачи не найден"));
        PaymentLinkService paymentLinkService = paymentLinkServiceProvider.getIfAvailable();
        if (paymentLinkService != null) {
            paymentLinkService.retireOpenLinksBeforePayableChange(orderId, reason);
        }
        return new PreparedTaskPayableChange(orderId, commonDisposition);
    }

    private boolean applyLinkedCommonInvoiceChange(PreparedTaskPayableChange change, Long taskId) {
        if (change == null || change.orderId() == null) {
            return false;
        }
        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        return switch (change.commonDisposition()) {
            case NOT_LINKED -> false;
            case REFRESH_CURRENT_INVOICE ->
                    commonBillingService != null && commonBillingService.refreshLinkedOrderAmount(change.orderId());
            case SUPPLEMENT_REQUIRED ->
                    commonBillingService != null
                            && commonBillingService.createBadReviewSupplementSuccessor(change.orderId(), taskId);
        };
    }

    private record PreparedTaskPayableChange(
            Long orderId,
            CommonPayableChangeDisposition commonDisposition
    ) {
    }

    private BadReviewTaskStatus taskStatus(Long taskId) {
        validateTaskIdAndAccess(taskId);
        return badReviewTaskRepository.findStatusById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Плохая задача не найдена: " + taskId));
    }

    private void validateTaskIdAndAccess(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new EntityNotFoundException("Плохая задача не найдена");
        }
        assignmentMutationGuardService.assertBadTask(taskId);
    }

    @Override
    @Transactional
    public void deleteOrderReadyReminder(Order order) {
        User managerUser = managerUser(order);
        Long orderId = order != null ? order.getId() : null;
        if (managerUser == null || orderId == null) {
            return;
        }

        personalReminderService.deleteSystemReminderBySource(
                managerUser,
                PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY,
                orderId
        );
    }

    @Override
    @Transactional
    public int deleteAllByOrderId(Long orderId) {
        if (orderId == null) {
            return 0;
        }
        int deleted = badReviewTaskRepository.deleteAllByOrderId(orderId);
        log.info("Удалено плохих задач заказа {}: {}", orderId, deleted);
        return deleted;
    }

    @Override
    @Transactional
    public BadReviewTask changeTaskBot(Long taskId) {
        BadReviewTask task = requireTask(taskId);
        Bot oldBot = task.getBot();
        BotSelection nextSelection = pickReplacementBot(task);
        Bot nextBot = nextSelection != null ? nextSelection.bot() : null;
        if (nextBot == null) {
            throw new IllegalStateException("Нет доступных аккаунтов для плохой задачи");
        }

        markReleasedIfChanged(oldBot, nextBot, "bad review task bot changed");
        task.setCrossCityBot(nextSelection.crossCity());
        if (nextSelection.crossCity()) {
            botCooldownService.markReservedUntilTaskCompletion(nextBot, "bad review task " + task.getId());
        }
        task.setBot(nextBot);
        task.setBotLoginSnapshot(botLogin(nextBot));
        task.setBotPasswordSnapshot(botPassword(nextBot));
        task.setBotFioSnapshot(botFio(nextBot));
        syncSourceReviewBot(task, nextBot);
        return badReviewTaskRepository.save(task);
    }

    @Override
    @Transactional
    public BadReviewTask deactivateAndChangeTaskBot(Long taskId, Long botId) {
        BadReviewTask task = requireTask(taskId);
        Long attachedBotId = task.getBot() != null ? task.getBot().getId() : null;
        assertRequestedBotIsCurrent(botId, attachedBotId);
        Long currentBotId = botId != null && botId > 0 ? botId : attachedBotId;

        if (currentBotId != null && currentBotId > 0) {
            Bot bot = botService.findBotById(currentBotId);
            if (bot != null) {
                boolean oldActive = bot.isActive();
                bot.setActive(false);
                auditActiveChange(bot, oldActive, false, "bad review task block button");
                botService.save(bot);
            }
        }

        return changeTaskBot(taskId);
    }

    private void assertRequestedBotIsCurrent(Long requestedBotId, Long currentBotId) {
        if (requestedBotId != null && requestedBotId < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_BOT_ID);
        }
        if (requestedBotId != null && requestedBotId > 0 && !Objects.equals(requestedBotId, currentBotId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, BOT_BINDING_CONFLICT);
        }
    }

    @Override
    public List<BadReviewTask> getTasksByOrderId(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        return badReviewTaskRepository.findAllByOrderIdOrderByCreatedDesc(orderId);
    }

    @Override
    public BadReviewTaskSummary getSummaryForOrder(Long orderId) {
        if (orderId == null) {
            return BadReviewTaskSummary.empty();
        }
        return summaryFromRows(badReviewTaskRepository.summarizeByOrderId(orderId));
    }

    @Override
    public Map<Long, BadReviewTaskSummary> getSummaryByOrderIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, MutableSummary> mutableSummaries = new HashMap<>();
        for (Object[] row : badReviewTaskRepository.summarizeByOrderIds(orderIds)) {
            Long orderId = rowLong(row[0]);
            BadReviewTaskStatus status = (BadReviewTaskStatus) row[1];
            long count = rowLong(row[2]);
            BigDecimal sum = rowMoney(row[3]);
            mutableSummaries.computeIfAbsent(orderId, key -> new MutableSummary()).add(status, count, sum);
        }

        Map<Long, BadReviewTaskSummary> result = new HashMap<>();
        mutableSummaries.forEach((orderId, summary) -> result.put(orderId, summary.toSummary()));
        return result;
    }

    @Override
    public BigDecimal getPayableSum(Order order) {
        if (order == null || order.getId() == null || order.getSum() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Не удалось достоверно определить сумму заказа с дополнительными задачами");
        }
        return payableSum(order, getSummaryForOrder(order.getId()));
    }

    @Override
    public int getPayableAmount(Order order) {
        int baseAmount = order != null ? order.getAmount() : 0;
        BadReviewTaskSummary summary = order == null ? BadReviewTaskSummary.empty() : getSummaryForOrder(order.getId());
        return baseAmount + summary.done();
    }

    @Override
    public void enrichOrderList(List<OrderDTOList> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }

        List<Long> orderIds = orders.stream()
                .map(OrderDTOList::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, BadReviewTaskSummary> summaries = getSummaryByOrderIds(orderIds);

        for (OrderDTOList order : orders) {
            BadReviewTaskSummary summary = summaries.getOrDefault(order.getId(), BadReviewTaskSummary.empty());
            if (order.getId() == null || order.getSum() == null
                    || order.getSum().compareTo(BigDecimal.ZERO) < 0
                    || summary.doneSum() == null
                    || summary.doneSum().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException("Не удалось достоверно определить сумму заказа с дополнительными задачами");
            }
            BigDecimal baseSum = order.getSum();
            order.setBadReviewTasksTotal(summary.pending() + summary.done());
            order.setBadReviewTasksPending(summary.pending());
            order.setBadReviewTasksDone(summary.done());
            order.setBadReviewTasksCanceled(summary.canceled());
            order.setBadReviewTasksSum(summary.doneSum());
            order.setTotalSumWithBadReviews(baseSum.add(summary.doneSum()));
        }
    }

    @Override
    public Page<BadReviewTask> getDueTasksToAdmin(LocalDate date, String keyword, Pageable pageable) {
        return badReviewTaskRepository.findDueTasksToAdmin(BadReviewTaskStatus.NEW, safeDate(date), keyword(keyword), pageable);
    }

    @Override
    public Page<BadReviewTask> getDueTasksToOwner(Collection<Manager> managers, LocalDate date, String keyword, Pageable pageable) {
        if (managers == null || managers.isEmpty()) {
            return emptyPage(pageable);
        }
        return badReviewTaskRepository.findDueTasksToOwner(managers, BadReviewTaskStatus.NEW, safeDate(date), keyword(keyword), pageable);
    }

    @Override
    public Page<BadReviewTask> getDueTasksToManager(Manager manager, LocalDate date, String keyword, Pageable pageable) {
        if (manager == null) {
            return emptyPage(pageable);
        }
        return badReviewTaskRepository.findDueTasksToManager(manager, BadReviewTaskStatus.NEW, safeDate(date), keyword(keyword), pageable);
    }

    @Override
    public Page<BadReviewTask> getDueTasksToWorker(Worker worker, LocalDate date, String keyword, Pageable pageable) {
        if (worker == null) {
            return emptyPage(pageable);
        }
        return badReviewTaskRepository.findDueTasksToWorker(worker, BadReviewTaskStatus.NEW, safeDate(date), keyword(keyword), pageable);
    }

    @Override
    public int countDueTasksToAdmin(LocalDate date) {
        return toIntCount(badReviewTaskRepository.countByStatusAndScheduledDateLessThanEqual(BadReviewTaskStatus.NEW, safeDate(date)));
    }

    @Override
    public int countDueTasksToOwner(Collection<Manager> managers, LocalDate date) {
        if (managers == null || managers.isEmpty()) {
            return 0;
        }
        return toIntCount(badReviewTaskRepository.countByStatusAndScheduledDateLessThanEqualAndOrderManagerIn(
                BadReviewTaskStatus.NEW,
                safeDate(date),
                managers
        ));
    }

    @Override
    public int countDueTasksToManager(Manager manager, LocalDate date) {
        if (manager == null) {
            return 0;
        }
        return toIntCount(badReviewTaskRepository.countByStatusAndScheduledDateLessThanEqualAndOrderManager(
                BadReviewTaskStatus.NEW,
                safeDate(date),
                manager
        ));
    }

    @Override
    public int countDueTasksToWorker(Worker worker, LocalDate date) {
        if (worker == null) {
            return 0;
        }
        return toIntCount(badReviewTaskRepository.countByStatusAndScheduledDateLessThanEqualAndWorker(
                BadReviewTaskStatus.NEW,
                safeDate(date),
                worker
        ));
    }

    private boolean hasActiveOrDoneTask(Long orderId, Long reviewId) {
        return badReviewTaskRepository.existsByOrderIdAndSourceReviewIdAndStatusIn(
                orderId,
                reviewId,
                EnumSet.of(BadReviewTaskStatus.NEW, BadReviewTaskStatus.DONE)
        );
    }

    private Worker resolveWorker(Order order, Review review) {
        if (order != null && order.getWorker() != null) {
            return order.getWorker();
        }
        return review != null ? review.getWorker() : null;
    }

    private String botLogin(Bot bot) {
        return bot != null && bot.getLogin() != null ? bot.getLogin().trim() : "";
    }

    private String botPassword(Bot bot) {
        return bot != null && bot.getPassword() != null ? bot.getPassword().trim() : "";
    }

    private String botFio(Bot bot) {
        return bot != null && bot.getFio() != null ? bot.getFio().trim() : "";
    }

    private String reviewText(Review review) {
        return review != null && review.getText() != null ? review.getText().trim() : "";
    }

    private BigDecimal resolveTaskPrice(Order order, Review review) {
        if (review != null && review.getPrice() != null) {
            return review.getPrice();
        }

        Product reviewProduct = review != null ? review.getProduct() : null;
        if (reviewProduct != null && reviewProduct.getPrice() != null) {
            return reviewProduct.getPrice();
        }

        OrderDetails details = review != null ? review.getOrderDetails() : null;
        Product detailsProduct = details != null ? details.getProduct() : null;
        if (detailsProduct != null && detailsProduct.getPrice() != null) {
            return detailsProduct.getPrice();
        }

        if (details != null && details.getPrice() != null) {
            int amount = details.getAmount();
            return amount > 0
                    ? details.getPrice().divide(BigDecimal.valueOf(amount), 2, RoundingMode.HALF_UP)
                    : details.getPrice();
        }

        if (order != null && order.getAmount() > 0 && order.getSum() != null) {
            return order.getSum().divide(BigDecimal.valueOf(order.getAmount()), 2, RoundingMode.HALF_UP);
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Не удалось достоверно определить стоимость дополнительной задачи"
        );
    }

    private BigDecimal validatedTaskPrice(Order order, Review review) {
        BigDecimal price = resolveTaskPrice(order, review);
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Стоимость дополнительной задачи должна быть больше нуля"
            );
        }
        return price;
    }

    private BadReviewTask requireTask(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new EntityNotFoundException("Плохая задача не найдена");
        }
        assignmentMutationGuardService.assertBadTask(taskId);
        return badReviewTaskRepository.findByIdForMutation(taskId)
                .or(() -> badReviewTaskRepository.findById(taskId))
                .orElseThrow(() -> new EntityNotFoundException("Плохая задача не найдена: " + taskId));
    }

    private boolean isPaid(BadReviewTask task) {
        Order order = task != null ? task.getOrder() : null;
        String status = order != null && order.getStatus() != null ? order.getStatus().getTitle() : "";
        return order != null && (order.isComplete() || "Оплачено".equals(status));
    }

    private BotSelection pickReplacementBot(BadReviewTask task) {
        City city = task != null && task.getSourceReview() != null
                && task.getSourceReview().getFilial() != null
                ? task.getSourceReview().getFilial().getCity()
                : null;
        if (city == null || city.getId() == null) {
            return null;
        }

        Long currentBotId = currentBotId(task);
        Set<Long> excludedBotIds = replacementExcludedBotIds(task, currentBotId);
        ReviewBotAssignmentGuardService.AssignmentScope assignmentScope = assignmentScope(task);
        excludedBotIds.addAll(assignmentGuardService.blockedBotIds(assignmentScope));
        List<Bot> candidates = botService.getFindAllByFilialCityId(city.getId()).stream()
                .filter(Objects::nonNull)
                .filter(Bot::isActive)
                .filter(bot -> bot.getId() != null)
                .filter(botCooldownService::isAvailableForAssignment)
                .filter(bot -> !excludedBotIds.contains(bot.getId()))
                .toList();

        List<Bot> preferredCityBots = candidates.stream()
                .filter(bot -> bot.getCounter() >= BAD_REVIEW_PREFERRED_COUNTER)
                .toList();
        if (!preferredCityBots.isEmpty()) {
            Bot selected = lockRandomEligible(preferredCityBots, assignmentScope);
            if (selected != null) {
                return new BotSelection(selected, false);
            }
        }

        List<Bot> crossCityBots = botService
                .getActiveBotsOutsideCityWithCounterAtLeast(city.getId(), BAD_REVIEW_PREFERRED_COUNTER)
                .stream()
                .filter(Objects::nonNull)
                .filter(bot -> bot.getId() != null)
                .filter(botCooldownService::isAvailableForAssignment)
                .filter(bot -> !excludedBotIds.contains(bot.getId()))
                .toList();
        if (!crossCityBots.isEmpty()) {
            Bot selected = lockRandomEligible(crossCityBots, assignmentScope);
            if (selected != null) {
                return new BotSelection(selected, true);
            }
        }

        if (!candidates.isEmpty()) {
            Bot selected = lockRandomEligible(candidates, assignmentScope);
            if (selected != null) {
                return new BotSelection(selected, false);
            }
        }

        Bot claimed = botService.claimReserveBotForCity(city, excludedBotIds).orElse(null);
        if (claimed == null) {
            return null;
        }
        Bot lockedClaimed = assignmentGuardService.lockIfEligible(claimed, assignmentScope)
                .orElseThrow(() -> new IllegalStateException(
                        "Резервный аккаунт уже использовался компанией или занят другой карточкой"
                ));
        return new BotSelection(lockedClaimed, false);
    }

    private Bot lockRandomEligible(
            List<Bot> candidates,
            ReviewBotAssignmentGuardService.AssignmentScope scope
    ) {
        List<Bot> remaining = new java.util.ArrayList<>(candidates);
        while (!remaining.isEmpty()) {
            Bot selected = remaining.remove(random.nextInt(remaining.size()));
            Bot locked = assignmentGuardService.lockIfEligible(selected, scope).orElse(null);
            if (locked != null) {
                return locked;
            }
        }
        return null;
    }

    private ReviewBotAssignmentGuardService.AssignmentScope assignmentScope(BadReviewTask task) {
        Long companyId = task != null
                && task.getOrder() != null
                && task.getOrder().getCompany() != null
                ? task.getOrder().getCompany().getId()
                : null;
        return assignmentGuardService.scopeForBadTask(companyId, task != null ? task.getId() : null);
    }

    private Set<Long> replacementExcludedBotIds(BadReviewTask task, Long currentBotId) {
        Set<Long> excludedBotIds = new java.util.HashSet<>();
        if (currentBotId != null) {
            excludedBotIds.add(currentBotId);
        }
        try {
            Set<Long> reservedByReviews = reviewRepository.findReservedBotIdsByUnpublishedReviews(null);
            if (reservedByReviews != null) {
                excludedBotIds.addAll(reservedByReviews);
            }
        } catch (Exception e) {
            log.error("Ошибка при получении аккаунтов, занятых в неопубликованных отзывах", e);
        }
        try {
            Set<Long> reservedByBadTasks = badReviewTaskRepository.findBotIdsByStatus(
                    BadReviewTaskStatus.NEW,
                    task != null ? task.getId() : null
            );
            if (reservedByBadTasks != null) {
                excludedBotIds.addAll(reservedByBadTasks);
            }
        } catch (Exception e) {
            log.error("Ошибка при получении аккаунтов, занятых в плохих задачах", e);
        }
        excludedBotIds.remove(null);
        return excludedBotIds;
    }

    private void releaseCrossCityBotAfterCompletion(BadReviewTask task) {
        if (task == null || !task.isCrossCityBot() || task.getBot() == null) {
            return;
        }

        LocalDate baseDate = task.getCompletedDate() != null
                ? task.getCompletedDate()
                : contractorPaymentBusinessClock.today();
        botCooldownService.markReleasedFrom(task.getBot(), baseDate, "bad review cross-city task finished");
    }

    private Long currentBotId(BadReviewTask task) {
        if (task == null) {
            return null;
        }
        if (task.getBot() != null) {
            return task.getBot().getId();
        }
        Review review = task.getSourceReview();
        return review != null && review.getBot() != null ? review.getBot().getId() : null;
    }

    private void syncSourceReviewBot(BadReviewTask task, Bot bot) {
        Review review = task != null ? task.getSourceReview() : null;
        if (review == null || review.getId() == null || bot == null) {
            return;
        }

        markReleasedIfChanged(review.getBot(), bot, "bad review source review bot changed");
        review.setBot(bot);
        accountWalkScheduleService.synchronizeAfterAccountChange(review);
        reviewRepository.save(review);
    }

    private void markReleasedIfChanged(Bot oldBot, Bot newBot, String reason) {
        Long oldBotId = oldBot != null ? oldBot.getId() : null;
        Long newBotId = newBot != null ? newBot.getId() : null;
        if (oldBotId != null && !Objects.equals(oldBotId, newBotId)) {
            botCooldownService.markReleased(oldBot, reason);
        }
    }

    private record BotSelection(Bot bot, boolean crossCity) {
    }

    private BadReviewTaskSummary summaryFromRows(List<Object[]> rows) {
        MutableSummary summary = new MutableSummary();
        for (Object[] row : rows) {
            summary.add((BadReviewTaskStatus) row[0], rowLong(row[1]), rowMoney(row[2]));
        }
        return summary.toSummary();
    }

    private void createTaskCompletionReminder(BadReviewTask task, BadReviewTaskSummary summary) {
        User managerUser = managerUser(task != null ? task.getOrder() : null);
        if (managerUser == null || task == null || task.getId() == null) {
            return;
        }

        personalReminderService.deleteSystemReminderBySource(
                managerUser,
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                task.getId()
        );
        personalReminderService.createSystemReminderDueNow(
                managerUser,
                badTaskReminderTitle(task),
                badTaskReminderText(task, summary),
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                task.getId(),
                orderId(task)
        );
    }

    private void createOrderReadyReminderIfNeeded(Order order, BadReviewTaskSummary summary) {
        if (order == null || order.getId() == null || summary == null || summary.pending() > 0 || summary.done() <= 0) {
            return;
        }

        User managerUser = managerUser(order);
        if (managerUser == null) {
            return;
        }

        personalReminderService.deleteSystemReminderBySource(
                managerUser,
                PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY,
                order.getId()
        );
        personalReminderService.createSystemReminderDueNow(
                managerUser,
                badOrderReadyReminderTitle(order),
                badOrderReadyReminderText(order, summary),
                PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY,
                order.getId(),
                order.getId()
        );
    }

    private String badTaskReminderTitle(BadReviewTask task) {
        return limit("Плохой отзыв выполнен: " + companyTitle(task != null ? task.getOrder() : null), 120);
    }

    private String badTaskReminderText(BadReviewTask task, BadReviewTaskSummary summary) {
        Order order = task != null ? task.getOrder() : null;
        Long taskId = task != null ? task.getId() : null;
        return limit(
                "Компания: " + companyTitle(order)
                        + "\nЗаказ #" + (order != null && order.getId() != null ? order.getId() : "-")
                        + "\n" + chatLine(order)
                        + "\nПлохой отзыв #" + (taskId == null ? "-" : taskId) + " выполнен, можно отправить клиенту счет."
                        + "\nК оплате: " + money(payableSum(order, summary)) + " руб.",
                1000
        );
    }

    private String badOrderReadyReminderTitle(Order order) {
        return limit("Плохие отзывы завершены: " + companyTitle(order), 120);
    }

    private String badOrderReadyReminderText(Order order, BadReviewTaskSummary summary) {
        return limit(
                "Компания: " + companyTitle(order)
                        + "\nЗаказ #" + (order != null && order.getId() != null ? order.getId() : "-")
                        + "\n" + chatLine(order)
                        + "\nВсе плохие отзывы выполнены. Если клиент не оплатит, можно перевести заказ в Бан."
                        + "\nК оплате: " + money(payableSum(order, summary)) + " руб.",
                1000
        );
    }

    private BigDecimal payableSum(Order order, BadReviewTaskSummary summary) {
        if (order == null || order.getId() == null || order.getSum() == null
                || summary == null || summary.doneSum() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Не удалось достоверно определить сумму заказа с дополнительными задачами");
        }
        BigDecimal payable = order.getSum().add(summary.doneSum());
        if (order.getSum().compareTo(BigDecimal.ZERO) < 0
                || summary.doneSum().compareTo(BigDecimal.ZERO) < 0
                || payable.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Итоговая сумма заказа с дополнительными задачами некорректна");
        }
        return payable;
    }

    private String chatLine(Order order) {
        String chatUrl = order != null && order.getCompany() != null && order.getCompany().getUrlChat() != null
                ? order.getCompany().getUrlChat().trim()
                : "";
        return chatUrl.isBlank() ? "Чат: не указан" : "Чат: " + chatUrl;
    }

    private String companyTitle(Order order) {
        String title = order != null && order.getCompany() != null && order.getCompany().getTitle() != null
                ? order.getCompany().getTitle().trim()
                : "";
        return title.isBlank() ? "компания не указана" : title;
    }

    private User managerUser(Order order) {
        return order != null && order.getManager() != null ? order.getManager().getUser() : null;
    }

    private Long orderId(BadReviewTask task) {
        return task != null && task.getOrder() != null ? task.getOrder().getId() : null;
    }

    private String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
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

    private LocalDate safeDate(LocalDate date) {
        return date == null ? contractorPaymentBusinessClock.today() : date;
    }

    private String keyword(String keyword) {
        return "%" + (keyword == null ? "" : keyword.trim().toLowerCase()) + "%";
    }

    private Page<BadReviewTask> emptyPage(Pageable pageable) {
        return new PageImpl<>(List.of(), pageable, 0);
    }

    private long rowLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private BigDecimal rowMoney(Object value) {
        if (value instanceof BigDecimal money) {
            return money;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        throw new IllegalStateException("Некорректная сумма в статистике дополнительных задач");
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

    private void auditTaskCompleted(BadReviewTask task) {
        if (task == null || task.getId() == null) {
            return;
        }

        businessAuditService.recordSafely(
                WorkerTaskCompletionMonitorService.ACTION_TASK_COMPLETED,
                "bad_review_task",
                task.getId(),
                task.getOrder() == null ? null : task.getOrder().getId(),
                task.getSourceReview() == null ? null : task.getSourceReview().getId(),
                BadReviewTaskStatus.NEW,
                BadReviewTaskStatus.DONE,
                "Плохая задача отмечена выполненной"
        );
    }

    private void auditTaskCanceled(BadReviewTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        businessAuditService.recordSafely(
                "bad_review_task_reward_adjusted",
                "bad_review_task",
                task.getId(),
                task.getOrder() == null ? null : task.getOrder().getId(),
                task.getSourceReview() == null ? null : task.getSourceReview().getId(),
                BadReviewTaskStatus.DONE,
                BadReviewTaskStatus.CANCELED,
                "Отмена выполненной задачи; создана датированная отрицательная корректировка начисления"
        );
    }

    private static final class MutableSummary {
        private long pending;
        private long done;
        private long canceled;
        private BigDecimal pendingSum = BigDecimal.ZERO;
        private BigDecimal doneSum = BigDecimal.ZERO;

        void add(BadReviewTaskStatus status, long count, BigDecimal sum) {
            if (sum == null || sum.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException("Сумма дополнительных задач не определена");
            }
            if (status == BadReviewTaskStatus.DONE) {
                done += count;
                doneSum = doneSum.add(sum);
            } else if (status == BadReviewTaskStatus.CANCELED) {
                canceled += count;
            } else {
                pending += count;
                pendingSum = pendingSum.add(sum);
            }
        }

        BadReviewTaskSummary toSummary() {
            long total = pending + done + canceled;
            return new BadReviewTaskSummary(
                    toInt(total),
                    toInt(pending),
                    toInt(done),
                    toInt(canceled),
                    doneSum,
                    pendingSum
            );
        }

        private int toInt(long value) {
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
    }
}
