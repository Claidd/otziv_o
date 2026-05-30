package com.hunt.otziv.bad_reviews.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageAttempt;
import com.hunt.otziv.client_messages.model.ScheduledMessageAttemptStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageAttemptRepository;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.status.OrderStatusNotificationService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class BadReviewTaskServiceImpl implements BadReviewTaskService {

    private static final int DEFAULT_ORIGINAL_RATING = 5;
    private static final int DEFAULT_TARGET_RATING = 2;
    private static final int SCHEDULE_STEP_DAYS = 2;
    private static final String STATUS_NOT_PAID = "Не оплачено";

    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ReviewRepository reviewRepository;
    private final BotService botService;
    private final PersonalReminderService personalReminderService;
    private final AppSettingService appSettingService;
    private final OrderStatusNotificationService orderStatusNotificationService;
    private final ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final ScheduledClientMessageAttemptRepository clientMessageAttemptRepository;
    private final GamificationEventService gamificationEventService;
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
        LocalDate startDate = LocalDate.now();
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
                    .price(resolveTaskPrice(order, review))
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
    public BadReviewTask completeTask(Long taskId) {
        BadReviewTask task = requireTask(taskId);
        if (task.getStatus() != BadReviewTaskStatus.NEW) {
            return task;
        }

        task.setStatus(BadReviewTaskStatus.DONE);
        task.setCompletedDate(LocalDate.now());
        BadReviewTask savedTask = badReviewTaskRepository.save(task);
        gamificationEventService.recordBadReviewTaskDone(savedTask);
        runCompletionSideEffects(savedTask);
        log.info("Плохая задача {} выполнена, заказ {}, доплата {}",
                savedTask.getId(),
                savedTask.getOrder() != null ? savedTask.getOrder().getId() : null,
                savedTask.getPrice());
        return savedTask;
    }

    private void runCompletionSideEffects(BadReviewTask savedTask) {
        Long orderId = savedTask.getOrder() != null ? savedTask.getOrder().getId() : null;
        try {
            BadReviewTaskSummary summary = orderId == null ? BadReviewTaskSummary.empty() : getSummaryForOrder(orderId);
            expireStalePaymentLinks(savedTask.getOrder());
            sendBadReviewInvoiceIfEnabled(savedTask, summary);
            createTaskCompletionReminder(savedTask, summary);
            createOrderReadyReminderIfNeeded(savedTask.getOrder(), summary);
        } catch (RuntimeException e) {
            log.warn("Плохая задача {} уже отмечена выполненной, но пост-действия не завершились. orderId={}",
                    savedTask.getId(), orderId, e);
        }
    }

    private void sendBadReviewInvoiceIfEnabled(BadReviewTask task, BadReviewTaskSummary summary) {
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
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true)) {
            log.info("Счет после плохого отзыва пропущен настройкой, orderId={}, taskId={}", order.getId(), task.getId());
            recordBadReviewInvoiceAttempt(task, order, ScheduledMessageAttemptStatus.SKIPPED, null, "bad_review_invoice_disabled",
                    "Отправка счета после плохого отзыва выключена настройкой", badReviewInvoicePreview(order, summary), 0);
            return;
        }
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)) {
            log.info("Счет после плохого отзыва пропущен: моментальные клиентские сообщения выключены, orderId={}, taskId={}",
                    order.getId(), task.getId());
            recordBadReviewInvoiceAttempt(task, order, ScheduledMessageAttemptStatus.SKIPPED, null, "immediate_messages_disabled",
                    "Моментальные клиентские сообщения выключены", badReviewInvoicePreview(order, summary), 0);
            return;
        }

        long startedAt = System.currentTimeMillis();
        String message = null;
        try {
            message = badReviewInvoiceMessage(order, summary);
            String currentStatus = statusTitle(order);
            boolean sent = orderStatusNotificationService.sendInformationalMessageToClientChat(
                    order,
                    order.getManager() == null ? null : order.getManager().getClientId(),
                    order.getCompany() == null ? null : order.getCompany().getGroupId(),
                    message,
                    "счет после плохого отзыва"
            );
            if (sent) {
                log.info(
                        "Счет после плохого отзыва отправлен клиенту: orderId={}, taskId={}, amount={}",
                        order.getId(),
                        task.getId(),
                        money(payableSum(order, summary))
                );
                recordBadReviewInvoiceAttempt(task, order, ScheduledMessageAttemptStatus.SENT, "client-chat",
                        null, null, message, System.currentTimeMillis() - startedAt);
                scheduleBadReviewAutoBanIfReady(order, summary);
            } else {
                String reason = "Сообщение не отправлено, заказ остался в статусе \"" + currentStatus + "\"";
                log.warn(
                        "Счет после плохого отзыва не отправлен клиенту: orderId={}, taskId={}, statusLeft={}",
                        order.getId(),
                        task.getId(),
                        currentStatus
                );
                recordBadReviewInvoiceAttempt(task, order, ScheduledMessageAttemptStatus.FAILED, null,
                        "client_chat_send_failed", reason, message, System.currentTimeMillis() - startedAt);
                paymentInvoiceRetryScheduler.scheduleBadReviewInvoiceRetry(order);
            }
        } catch (Exception e) {
            String reason = readableException(e);
            log.warn("Счет после плохого отзыва не отправлен: orderId={}, taskId={}, reason={}",
                    order.getId(), task.getId(), reason, e);
            recordBadReviewInvoiceAttempt(task, order, ScheduledMessageAttemptStatus.FAILED, null,
                    "bad_review_invoice_exception", reason, message == null ? badReviewInvoicePreview(order, summary) : message,
                    System.currentTimeMillis() - startedAt);
            paymentInvoiceRetryScheduler.scheduleBadReviewInvoiceRetry(order);
        }
    }

    private void scheduleBadReviewAutoBanIfReady(Order order, BadReviewTaskSummary summary) {
        if (!isBadReviewFinalInvoice(summary)) {
            return;
        }
        paymentInvoiceRetryScheduler.scheduleBadReviewAutoBan(order);
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
    public BadReviewTask cancelTask(Long taskId) {
        BadReviewTask task = requireTask(taskId);
        if (isPaid(task)) {
            throw new IllegalStateException("После оплаты заказа отмена плохих задач не пересчитывает чек и ЗП");
        }
        if (task.getStatus() == BadReviewTaskStatus.CANCELED) {
            return task;
        }

        boolean wasDone = task.getStatus() == BadReviewTaskStatus.DONE;
        task.setStatus(BadReviewTaskStatus.CANCELED);
        BadReviewTask savedTask = badReviewTaskRepository.save(task);
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
            if (wasDone) {
                sendBadReviewInvoiceIfEnabled(savedTask, summary);
            }
            if (summary.pending() == 0 && summary.done() > 0) {
                createOrderReadyReminderIfNeeded(order, summary);
            } else {
                deleteOrderReadyReminder(order);
            }
        }
        return savedTask;
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
    public BadReviewTask changeTaskBot(Long taskId) {
        BadReviewTask task = requireTask(taskId);
        Bot nextBot = pickReplacementBot(task);
        if (nextBot == null) {
            throw new IllegalStateException("Нет доступных аккаунтов для плохой задачи");
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
        BigDecimal baseSum = order != null && order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
        BadReviewTaskSummary summary = order == null ? BadReviewTaskSummary.empty() : getSummaryForOrder(order.getId());
        return baseSum.add(summary.doneSum());
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
            BigDecimal baseSum = order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
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
        if (review != null && review.getWorker() != null) {
            return review.getWorker();
        }
        return order != null ? order.getWorker() : null;
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

        return BigDecimal.ZERO;
    }

    private BadReviewTask requireTask(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new EntityNotFoundException("Плохая задача не найдена");
        }
        return badReviewTaskRepository.findByIdForMutation(taskId)
                .or(() -> badReviewTaskRepository.findById(taskId))
                .orElseThrow(() -> new EntityNotFoundException("Плохая задача не найдена: " + taskId));
    }

    private boolean isPaid(BadReviewTask task) {
        Order order = task != null ? task.getOrder() : null;
        String status = order != null && order.getStatus() != null ? order.getStatus().getTitle() : "";
        return order != null && (order.isComplete() || "Оплачено".equals(status));
    }

    private Bot pickReplacementBot(BadReviewTask task) {
        City city = task != null && task.getSourceReview() != null
                && task.getSourceReview().getFilial() != null
                ? task.getSourceReview().getFilial().getCity()
                : null;
        if (city == null || city.getId() == null) {
            return null;
        }

        Long currentBotId = currentBotId(task);
        List<Bot> candidates = botService.getFindAllByFilialCityId(city.getId()).stream()
                .filter(Objects::nonNull)
                .filter(Bot::isActive)
                .filter(bot -> bot.getId() != null)
                .filter(bot -> !Objects.equals(bot.getId(), currentBotId))
                .toList();

        if (!candidates.isEmpty()) {
            return candidates.get(random.nextInt(candidates.size()));
        }

        return botService.claimReserveBotForCity(city, currentBotId == null ? Set.of() : Set.of(currentBotId))
                .orElse(null);
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

        review.setBot(bot);
        reviewRepository.save(review);
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
        BigDecimal baseSum = order != null && order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
        BigDecimal doneSum = summary == null ? BigDecimal.ZERO : summary.doneSum();
        return baseSum.add(doneSum);
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
        return date == null ? LocalDate.now() : date;
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
        return BigDecimal.ZERO;
    }

    private int toIntCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private static final class MutableSummary {
        private long pending;
        private long done;
        private long canceled;
        private BigDecimal pendingSum = BigDecimal.ZERO;
        private BigDecimal doneSum = BigDecimal.ZERO;

        void add(BadReviewTaskStatus status, long count, BigDecimal sum) {
            if (status == BadReviewTaskStatus.DONE) {
                done += count;
                doneSum = doneSum.add(sum == null ? BigDecimal.ZERO : sum);
            } else if (status == BadReviewTaskStatus.CANCELED) {
                canceled += count;
            } else {
                pending += count;
                pendingSum = pendingSum.add(sum == null ? BigDecimal.ZERO : sum);
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
