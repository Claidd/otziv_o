package com.hunt.otziv.gamification.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.gamification.dto.GamificationActorProgressResponse;
import com.hunt.otziv.gamification.dto.GamificationEventResponse;
import com.hunt.otziv.gamification.dto.GamificationEventTypeProgressResponse;
import com.hunt.otziv.gamification.dto.GamificationProgressResponse;
import com.hunt.otziv.gamification.dto.GamificationScorePreviewActorResponse;
import com.hunt.otziv.gamification.dto.GamificationScorePreviewResponse;
import com.hunt.otziv.gamification.model.GamificationEvent;
import com.hunt.otziv.gamification.model.GamificationRule;
import com.hunt.otziv.gamification.repository.GamificationEventRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class GamificationEventService {

    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_WORKER = "WORKER";
    public static final String ROLE_SYSTEM = "SYSTEM";

    public static final String REVIEW_PUBLISHED = "REVIEW_PUBLISHED";
    public static final String ORDER_PAID = "ORDER_PAID";
    public static final String BAD_REVIEW_TASK_DONE = "BAD_REVIEW_TASK_DONE";
    public static final String REVIEW_RECOVERY_TASK_DONE = "REVIEW_RECOVERY_TASK_DONE";

    private final GamificationEventRepository repository;
    private final GamificationSettingsService settingsService;
    private final GamificationRuleService ruleService;
    private final GamificationShadowScoreService shadowScoreService;
    private final GamificationTimelinessService timelinessService;

    @Transactional(readOnly = true)
    public List<GamificationEventResponse> latestEvents(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public GamificationProgressResponse progress(int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(safeDays - 1L);
        LocalDateTime fromInclusive = from.atStartOfDay();
        LocalDateTime toExclusive = to.plusDays(1).atStartOfDay();

        long total = repository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(fromInclusive, toExclusive);
        List<GamificationEventTypeProgressResponse> eventTypes = eventTypeProgress(fromInclusive, toExclusive);
        List<GamificationActorProgressResponse> topActors = repository
                .topActors(fromInclusive, toExclusive, PageRequest.of(0, 10))
                .stream()
                .map(this::actorProgress)
                .toList();

        return new GamificationProgressResponse(from, to, safeDays, total, eventTypes, topActors);
    }

    @Transactional(readOnly = true)
    public GamificationScorePreviewResponse scorePreview(int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(safeDays - 1L);
        LocalDateTime fromInclusive = from.atStartOfDay();
        LocalDateTime toExclusive = to.plusDays(1).atStartOfDay();
        Map<String, GamificationRule> rules = ruleService.readRules();
        Map<String, ScoreAccumulator> actors = new LinkedHashMap<>();

        for (GamificationEvent event : repository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(fromInclusive, toExclusive)) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            GamificationRule rule = rules.get(event.getEventType());
            if (rule == null || !rule.isEnabled() || rule.getPoints() <= 0) {
                continue;
            }

            ScoreAccumulator actor = actors.computeIfAbsent(
                    actorKey(event.getActorUserId(), event.getActorName(), event.getActorRole()),
                    key -> new ScoreAccumulator(event.getActorUserId(), event.getActorName(), event.getActorRole())
            );
            actor.totalEvents++;
            actor.totalPoints += timelinessService.score(rule.getPoints(), event.getTimelinessMultiplier());
        }

        long totalPoints = actors.values().stream()
                .mapToLong(ScoreAccumulator::totalPoints)
                .sum();
        List<GamificationScorePreviewActorResponse> topActors = actors.values().stream()
                .sorted(Comparator.comparingLong(ScoreAccumulator::totalPoints).reversed())
                .limit(20)
                .map(ScoreAccumulator::response)
                .toList();
        return new GamificationScorePreviewResponse(from, to, safeDays, totalPoints, topActors);
    }

    public void recordReviewPublished(Review review) {
        if (review == null || review.getId() == null) {
            return;
        }
        saveSafely(reviewPublishedEvent(review, null));
    }

    public void recordOrderPaid(Order order) {
        if (order == null || order.getId() == null) {
            return;
        }
        saveSafely(orderPaidEvent(order, null));
    }

    public void recordBadReviewTaskDone(BadReviewTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        saveSafely(badReviewTaskDoneEvent(task, null));
    }

    public void recordReviewRecoveryTaskDone(ReviewRecoveryTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        saveSafely(reviewRecoveryTaskDoneEvent(task, null));
    }

    public boolean backfillReviewPublished(Review review) {
        return saveBackfilled(reviewPublishedEvent(review, sourceDateTime(review == null ? null : review.getPublishedDate())));
    }

    public boolean backfillOrderPaid(Order order) {
        return saveBackfilled(orderPaidEvent(order, sourceDateTime(order == null ? null : order.getPayDay())));
    }

    public boolean backfillBadReviewTaskDone(BadReviewTask task) {
        return saveBackfilled(badReviewTaskDoneEvent(task, sourceDateTime(task == null ? null : task.getCompletedDate())));
    }

    public boolean backfillReviewRecoveryTaskDone(ReviewRecoveryTask task) {
        return saveBackfilled(reviewRecoveryTaskDoneEvent(task, sourceDateTime(task == null ? null : task.getCompletedDate())));
    }

    private GamificationEvent reviewPublishedEvent(Review review, LocalDateTime createdAt) {
        if (review == null || review.getId() == null) {
            return null;
        }
        Order order = order(review);
        Worker worker = review.getWorker() != null ? review.getWorker() : order != null ? order.getWorker() : null;
        Manager manager = order != null ? order.getManager() : null;
        Company company = order != null ? order.getCompany() : null;

        GamificationEvent event = GamificationEvent.builder()
                .eventType(REVIEW_PUBLISHED)
                .actorRole(ROLE_WORKER)
                .actorUserId(userId(worker))
                .actorName(userName(worker))
                .orderId(id(order))
                .reviewId(review.getId())
                .workerId(id(worker))
                .managerId(id(manager))
                .companyId(id(company))
                .companyTitle(title(company))
                .source(createdAt == null ? "review.publish" : "review.publish.backfill")
                .uniqueEventKey(REVIEW_PUBLISHED + ":" + review.getId())
                .payload("publishedDate=" + review.getPublishedDate())
                .createdAt(createdAt)
                .build();
        timelinessService.apply(event, review.getPublishedDate(), actualDate(createdAt));
        return event;
    }

    private GamificationEvent orderPaidEvent(Order order, LocalDateTime createdAt) {
        if (order == null || order.getId() == null) {
            return null;
        }
        Manager manager = order.getManager();
        Worker worker = order.getWorker();
        Company company = order.getCompany();
        String actorRole = manager != null && manager.getUser() != null ? ROLE_MANAGER : worker != null ? ROLE_WORKER : ROLE_SYSTEM;

        GamificationEvent event = GamificationEvent.builder()
                .eventType(ORDER_PAID)
                .actorRole(actorRole)
                .actorUserId(ROLE_MANAGER.equals(actorRole) ? userId(manager) : userId(worker))
                .actorName(ROLE_MANAGER.equals(actorRole) ? userName(manager) : userName(worker))
                .orderId(order.getId())
                .workerId(id(worker))
                .managerId(id(manager))
                .companyId(id(company))
                .companyTitle(title(company))
                .source(createdAt == null ? "order.status" : "order.status.backfill")
                .uniqueEventKey(ORDER_PAID + ":" + order.getId())
                .payload("sum=" + safeMoney(order.getSum()) + ";amount=" + order.getAmount())
                .createdAt(createdAt)
                .build();
        timelinessService.apply(event, order.getPayDay(), order.getPayDay());
        return event;
    }

    private GamificationEvent badReviewTaskDoneEvent(BadReviewTask task, LocalDateTime createdAt) {
        if (task == null || task.getId() == null) {
            return null;
        }
        Order order = task.getOrder();
        Worker worker = task.getWorker() != null ? task.getWorker() : order != null ? order.getWorker() : null;
        Manager manager = order != null ? order.getManager() : null;
        Company company = order != null ? order.getCompany() : null;

        GamificationEvent event = GamificationEvent.builder()
                .eventType(BAD_REVIEW_TASK_DONE)
                .actorRole(ROLE_WORKER)
                .actorUserId(userId(worker))
                .actorName(userName(worker))
                .orderId(id(order))
                .reviewId(id(task.getSourceReview()))
                .badReviewTaskId(task.getId())
                .workerId(id(worker))
                .managerId(id(manager))
                .companyId(id(company))
                .companyTitle(title(company))
                .source(createdAt == null ? "bad-review.complete" : "bad-review.complete.backfill")
                .uniqueEventKey(BAD_REVIEW_TASK_DONE + ":" + task.getId())
                .payload("price=" + safeMoney(task.getPrice()) + ";scheduledDate=" + task.getScheduledDate() + ";completedDate=" + task.getCompletedDate())
                .createdAt(createdAt)
                .build();
        timelinessService.apply(event, task.getScheduledDate(), actualDate(createdAt, task.getCompletedDate()));
        return event;
    }

    private GamificationEvent reviewRecoveryTaskDoneEvent(ReviewRecoveryTask task, LocalDateTime createdAt) {
        if (task == null || task.getId() == null) {
            return null;
        }
        Order order = task.getOrder();
        Worker worker = task.getWorker() != null ? task.getWorker() : order != null ? order.getWorker() : null;
        Manager manager = task.getManager() != null ? task.getManager() : order != null ? order.getManager() : null;
        Company company = order != null ? order.getCompany() : null;

        GamificationEvent event = GamificationEvent.builder()
                .eventType(REVIEW_RECOVERY_TASK_DONE)
                .actorRole(ROLE_WORKER)
                .actorUserId(task.getCompletedBy() != null ? task.getCompletedBy().getId() : userId(worker))
                .actorName(task.getCompletedBy() != null ? userName(task.getCompletedBy()) : userName(worker))
                .orderId(id(order))
                .reviewId(id(task.getSourceReview()))
                .recoveryTaskId(task.getId())
                .workerId(id(worker))
                .managerId(id(manager))
                .companyId(id(company))
                .companyTitle(title(company))
                .source(createdAt == null ? "review-recovery.complete" : "review-recovery.complete.backfill")
                .uniqueEventKey(REVIEW_RECOVERY_TASK_DONE + ":" + task.getId())
                .payload("scheduledDate=" + task.getScheduledDate() + ";completedDate=" + task.getCompletedDate())
                .createdAt(createdAt)
                .build();
        timelinessService.apply(event, task.getScheduledDate(), actualDate(createdAt, task.getCompletedDate()));
        return event;
    }

    private void saveSafely(GamificationEvent event) {
        if (event == null || !settingsService.isEventsEnabledForRole(event.getActorRole())) {
            return;
        }
        try {
            if (event.getUniqueEventKey() != null && repository.existsByUniqueEventKey(event.getUniqueEventKey())) {
                return;
            }
            GamificationEvent savedEvent = repository.save(event);
            shadowScoreService.recordForEvent(savedEvent);
        } catch (DataIntegrityViolationException e) {
            log.debug("Gamification event duplicate skipped: {}", event.getUniqueEventKey());
        } catch (RuntimeException e) {
            log.warn("Gamification event was not recorded: type={}, key={}",
                    event.getEventType(), event.getUniqueEventKey(), e);
        }
    }

    private boolean saveBackfilled(GamificationEvent event) {
        if (event == null) {
            return false;
        }
        try {
            if (event.getUniqueEventKey() != null && repository.existsByUniqueEventKey(event.getUniqueEventKey())) {
                updateBackfilledMetadata(event);
                return false;
            }
            GamificationEvent savedEvent = repository.save(event);
            shadowScoreService.recordForEvent(savedEvent);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("Gamification backfill duplicate skipped: {}", event.getUniqueEventKey());
        } catch (RuntimeException e) {
            log.warn("Gamification backfill event was not recorded: type={}, key={}",
                    event.getEventType(), event.getUniqueEventKey(), e);
        }
        return false;
    }

    private boolean updateBackfilledMetadata(GamificationEvent event) {
        if (event == null || event.getUniqueEventKey() == null) {
            return false;
        }
        return repository.findByUniqueEventKey(event.getUniqueEventKey())
                .map(existing -> {
                    existing.setPlannedDate(event.getPlannedDate());
                    existing.setActualDate(event.getActualDate());
                    existing.setDelayDays(event.getDelayDays());
                    existing.setTimelinessBucket(event.getTimelinessBucket());
                    existing.setTimelinessMultiplier(event.getTimelinessMultiplier());
                    existing.setPayload(event.getPayload());
                    repository.save(existing);
                    return false;
                })
                .orElse(false);
    }

    private GamificationEventResponse toResponse(GamificationEvent event) {
        return new GamificationEventResponse(
                event.getId(),
                event.getEventType(),
                event.getActorUserId(),
                event.getActorRole(),
                event.getActorName(),
                event.getOrderId(),
                event.getReviewId(),
                event.getBadReviewTaskId(),
                event.getRecoveryTaskId(),
                event.getWorkerId(),
                event.getManagerId(),
                event.getCompanyId(),
                event.getCompanyTitle(),
                event.getSource(),
                event.getPayload(),
                event.getPlannedDate(),
                event.getActualDate(),
                event.getDelayDays(),
                event.getTimelinessBucket(),
                event.getTimelinessMultiplier(),
                event.getCreatedAt()
        );
    }

    private List<GamificationEventTypeProgressResponse> eventTypeProgress(
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(REVIEW_PUBLISHED, 0L);
        counts.put(ORDER_PAID, 0L);
        counts.put(BAD_REVIEW_TASK_DONE, 0L);
        counts.put(REVIEW_RECOVERY_TASK_DONE, 0L);
        for (Object[] row : repository.countByEventType(fromInclusive, toExclusive)) {
            if (row == null || row.length < 2) {
                continue;
            }
            counts.put(String.valueOf(row[0]), number(row[1]));
        }
        return counts.entrySet().stream()
                .map(entry -> new GamificationEventTypeProgressResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private GamificationActorProgressResponse actorProgress(Object[] row) {
        Long actorUserId = row != null && row.length > 0 ? numberOrNull(row[0]) : null;
        String actorName = row != null && row.length > 1 && row[1] != null ? String.valueOf(row[1]) : null;
        String actorRole = row != null && row.length > 2 && row[2] != null ? String.valueOf(row[2]) : null;
        long events = row != null && row.length > 3 ? number(row[3]) : 0L;
        return new GamificationActorProgressResponse(actorUserId, actorName, actorRole, events);
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private Long numberOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String actorKey(Long actorUserId, String actorName, String actorRole) {
        return (actorUserId == null ? "system" : actorUserId)
                + ":" + (actorRole == null ? "" : actorRole)
                + ":" + (actorName == null ? "" : actorName);
    }

    private Order order(Review review) {
        return review != null && review.getOrderDetails() != null ? review.getOrderDetails().getOrder() : null;
    }

    private Long userId(Worker worker) {
        return worker != null && worker.getUser() != null ? worker.getUser().getId() : null;
    }

    private Long userId(Manager manager) {
        return manager != null && manager.getUser() != null ? manager.getUser().getId() : null;
    }

    private String userName(Worker worker) {
        return worker != null ? userName(worker.getUser()) : null;
    }

    private String userName(Manager manager) {
        return manager != null ? userName(manager.getUser()) : null;
    }

    private String userName(User user) {
        if (user == null) {
            return null;
        }
        if (user.getFio() != null && !user.getFio().isBlank()) {
            return user.getFio();
        }
        return user.getUsername();
    }

    private Long id(Order order) {
        return order == null ? null : order.getId();
    }

    private Long id(Review review) {
        return review == null ? null : review.getId();
    }

    private Long id(Worker worker) {
        return worker == null ? null : worker.getId();
    }

    private Long id(Manager manager) {
        return manager == null ? null : manager.getId();
    }

    private Long id(Company company) {
        return company == null ? null : company.getId();
    }

    private String title(Company company) {
        return company == null ? null : company.getTitle();
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDateTime sourceDateTime(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private LocalDate actualDate(LocalDateTime createdAt) {
        return createdAt == null ? LocalDate.now() : createdAt.toLocalDate();
    }

    private LocalDate actualDate(LocalDateTime createdAt, LocalDate fallback) {
        return fallback != null ? fallback : actualDate(createdAt);
    }

    private static class ScoreAccumulator {
        private final Long actorUserId;
        private final String actorName;
        private final String actorRole;
        private long totalEvents;
        private long totalPoints;

        private ScoreAccumulator(Long actorUserId, String actorName, String actorRole) {
            this.actorUserId = actorUserId;
            this.actorName = actorName;
            this.actorRole = actorRole;
        }

        private long totalPoints() {
            return totalPoints;
        }

        private GamificationScorePreviewActorResponse response() {
            return new GamificationScorePreviewActorResponse(
                    actorUserId,
                    actorName,
                    actorRole,
                    totalEvents,
                    totalPoints
            );
        }
    }
}
