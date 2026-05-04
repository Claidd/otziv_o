package com.hunt.otziv.bad_reviews.services;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class BadReviewTaskServiceImpl implements BadReviewTaskService {

    private static final int DEFAULT_ORIGINAL_RATING = 5;
    private static final int DEFAULT_TARGET_RATING = 2;
    private static final int SCHEDULE_STEP_DAYS = 2;

    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ReviewRepository reviewRepository;
    private final BotService botService;
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

            BadReviewTask task = BadReviewTask.builder()
                    .order(order)
                    .sourceReview(review)
                    .worker(resolveWorker(order, review))
                    .bot(review.getBot())
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
        Long orderId = savedTask.getOrder() != null ? savedTask.getOrder().getId() : null;
        log.info("Плохая задача {} выполнена, заказ {}, доплата {}", savedTask.getId(), orderId, savedTask.getPrice());
        return savedTask;
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

        task.setStatus(BadReviewTaskStatus.CANCELED);
        return badReviewTaskRepository.save(task);
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

    private BigDecimal resolveTaskPrice(Order order, Review review) {
        if (review != null && review.getPrice() != null) {
            return review.getPrice();
        }

        OrderDetails details = review != null ? review.getOrderDetails() : null;
        if (details != null && details.getPrice() != null) {
            return details.getPrice();
        }

        Product reviewProduct = review != null ? review.getProduct() : null;
        if (reviewProduct != null && reviewProduct.getPrice() != null) {
            return reviewProduct.getPrice();
        }

        Product detailsProduct = details != null ? details.getProduct() : null;
        if (detailsProduct != null && detailsProduct.getPrice() != null) {
            return detailsProduct.getPrice();
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
        return badReviewTaskRepository.findById(taskId)
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

        Long currentBotId = task.getBot() != null ? task.getBot().getId() : null;
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

    private BadReviewTaskSummary summaryFromRows(List<Object[]> rows) {
        MutableSummary summary = new MutableSummary();
        for (Object[] row : rows) {
            summary.add((BadReviewTaskStatus) row[0], rowLong(row[1]), rowMoney(row[2]));
        }
        return summary.toSummary();
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
