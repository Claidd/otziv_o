package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.manager_control.dto.ManagerQueueStateResponse;
import com.hunt.otziv.manager_control.service.ManagerQueueStateService;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.model.ManagerPerformanceDaily;
import com.hunt.otziv.manager_daily_summary.model.ManagerSiteActivityEvent;
import com.hunt.otziv.manager_daily_summary.repository.ManagerPerformanceDailyRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSiteActivityEventRepository;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceGrade;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerDailySummaryService {

    public static final String FORMULA_VERSION = "manager-v2";
    private static final long REPLY_SLA_SECONDS = Duration.ofMinutes(30).toSeconds();
    private static final long EVENT_TAIL_SECONDS = 60;

    private final ManagerRepository managerRepository;
    private final ManagerPerformanceService performanceService;
    private final ManagerDailyControlRepository controlRepository;
    private final ManagerDailyControlItemRepository itemRepository;
    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final ClientChatMessageRepository messageRepository;
    private final ClientChatUnansweredItemRepository unansweredRepository;
    private final ManagerSiteActivityEventRepository activityRepository;
    private final ManagerPerformanceDailyRepository dailyRepository;
    private final AppSettingService appSettingService;
    private final JdbcTemplate jdbcTemplate;
    private final ManagerQueueStateService queueStateService;
    private final GamificationScoreLedgerRepository scoreLedgerRepository;
    private final GamificationEventService gamificationEventService;

    @Transactional
    public List<ManagerDailySummaryResponse> calculate(LocalDate requestedDate, boolean finalizeDay) {
        LocalDate date = requestedDate == null ? LocalDate.now() : requestedDate;
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();
        Map<Long, ManagerPerformanceScoreResponse> scores = performanceService.score(date).stream()
                .filter(row -> row.managerId() != null)
                .collect(Collectors.toMap(ManagerPerformanceScoreResponse::managerId, Function.identity(), (left, right) -> left));

        List<ManagerPerformanceDaily> saved = new ArrayList<>();
        for (Manager manager : managerRepository.findAllWithUserAndImage()) {
            saved.add(calculateManager(date, from, to, manager, scores.get(manager.getId()), finalizeDay));
        }
        rebuildMonthly(date.withDayOfMonth(1), !date.equals(LocalDate.now()) && date.equals(date.withDayOfMonth(date.lengthOfMonth())));
        rebuildYearly(date.withDayOfYear(1), !date.equals(LocalDate.now()) && date.equals(date.withDayOfYear(date.lengthOfYear())));
        if (date.getDayOfMonth() == 1) {
            LocalDate previousMonth = date.minusMonths(1).withDayOfMonth(1);
            rebuildMonthly(previousMonth, true);
        }
        if (date.getDayOfYear() == 1) {
            LocalDate previousYear = date.minusYears(1).withDayOfYear(1);
            rebuildYearly(previousYear, true);
        }
        return saved.stream()
                .sorted(Comparator.comparingInt(ManagerPerformanceDaily::getAdjustedScore).reversed())
                .map(this::response)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ManagerDailySummaryResponse> summaries(LocalDate requestedDate) {
        LocalDate date = requestedDate == null ? LocalDate.now() : requestedDate;
        return dailyRepository.findBySummaryDateOrderByAdjustedScoreDesc(date).stream().map(this::response).toList();
    }

    private ManagerPerformanceDaily calculateManager(
            LocalDate date,
            LocalDateTime from,
            LocalDateTime to,
            Manager manager,
            ManagerPerformanceScoreResponse score,
            boolean finalizeDay
    ) {
        ManagerDailyControl control = controlRepository.findByControlDateAndManager(date, manager).orElse(null);
        List<ManagerDailyControlItem> items = control == null ? List.of() : itemRepository.findByControl(control);
        List<ManagerDailyControlConcreteItem> concrete = control == null ? List.of() : concreteItemRepository.findByControl(control);
        TaskStats tasks = taskStats(items, concrete);
        ReplyStats replies = replyStats(messageRepository.findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(manager.getId(), from, to));
        ProblemStats problems = problemStats(concrete);
        ActivityStats activity = activityStats(manager.getId(), from, to,
                messageRepository.findByManager_IdAndMessageAtBetweenOrderByMessageAtAscIdAsc(manager.getId(), from, to));
        long unanswered = unansweredRepository.countByManagerAndStatusAndLastClientMessageAtLessThanEqual(
                manager, ClientChatUnansweredStatus.OPEN, to.minusNanos(1));

        ManagerPerformanceDaily daily = dailyRepository.findBySummaryDateAndManager_Id(date, manager.getId())
                .orElseGet(ManagerPerformanceDaily::new);
        daily.setSummaryDate(date);
        daily.setManager(manager);
        daily.setManagerUserId(manager.getUser() == null ? null : manager.getUser().getId());
        daily.setManagerName(manager.getUser() == null ? null : manager.getUser().getFio());
        daily.setBaseScore(score == null ? 0 : score.performanceScore());
        daily.setAdjustedScore(score == null ? 0 : score.loadAdjustedPerformanceScore());
        daily.setGrade(score == null ? grade(0) : score.grade());
        daily.setFormulaVersion(FORMULA_VERSION);
        daily.setTaskTotal(tasks.total());
        daily.setTaskCompleted(tasks.completed());
        daily.setTaskOpen(tasks.open());
        daily.setTaskProgressPercent(percent(tasks.completed(), tasks.total()));
        daily.setOverdueCount(items.stream()
                .filter(item -> "OVERDUE_ORDERS".equals(item.getReasonCode()))
                .mapToLong(ManagerDailyControlItem::getCount)
                .sum());
        daily.setRiskCount(score == null ? 0 : Math.max(0, score.backlogCount() - score.openCount() - unanswered));
        daily.setUnansweredCount(unanswered);
        daily.setFirstReplyCount(replies.first().count());
        daily.setFirstReplyTotalSeconds(replies.first().totalSeconds());
        daily.setFirstReplyAverageSeconds(replies.first().averageSeconds());
        daily.setFirstReplyMedianSeconds(replies.first().medianSeconds());
        daily.setFirstReplyP90Seconds(replies.first().p90Seconds());
        daily.setAllReplyCount(replies.all().count());
        daily.setAllReplyTotalSeconds(replies.all().totalSeconds());
        daily.setAllReplyAverageSeconds(replies.all().averageSeconds());
        daily.setAllReplyMedianSeconds(replies.all().medianSeconds());
        daily.setAllReplyP90Seconds(replies.all().p90Seconds());
        daily.setRepliesInSla(replies.all().inSla());
        daily.setReplyHistogram(replies.all().histogram());
        daily.setProblemCount(problems.count());
        daily.setProblemResolvedCount(problems.resolvedCount());
        daily.setProblemResolutionTotalSeconds(problems.totalResolutionSeconds());
        daily.setProblemResolutionAverageSeconds(problems.averageResolutionSeconds());
        daily.setSiteActiveSeconds(activity.siteSeconds());
        daily.setMessengerActiveSeconds(activity.messengerOutsideSiteSeconds());
        daily.setConfirmedActiveSeconds(activity.confirmedSeconds());
        daily.setLeadActionCount(items.stream().filter(item -> "LEADS".equals(item.getReasonCode())).mapToLong(ManagerDailyControlItem::getCount).sum());
        daily.setTargetSlaCount(replies.all().count());
        daily.setTargetSlaMetCount(replies.all().inSla());
        LocalDateTime queueUntil = date.equals(LocalDate.now()) && LocalDateTime.now().isBefore(to) ? LocalDateTime.now() : to;
        ManagerQueueStateResponse queue = queueStateService.aggregate(manager, date, queueUntil);
        daily.setHardSlaBreachCount(queue.hardBreachCount());
        daily.setControlledSeconds(queue.controlledSeconds());
        daily.setCleanQueueSeconds(queue.cleanQueueSeconds());
        long xp = manager.getUser() == null ? 0 : number(scoreLedgerRepository.pointsForActorBetween(manager.getUser().getId(), from, to));
        daily.setXpEarned(xp);
        int targetPercent = Math.max(1, Math.min(100, appSettingService.getInt("manager.sla.day-target-percent", 90)));
        int controlPercent = queue.controlPercent();
        long slaPercent = replies.all().count() == 0 ? 100 : Math.round(replies.all().inSla() * 100.0 / replies.all().count());
        int stars = queue.hardBreachCount() == 0 ? 1 : 0;
        if (stars > 0 && slaPercent >= targetPercent) stars++;
        if (stars > 1 && controlPercent >= targetPercent && queue.cleanQueueSeconds() > 0) stars++;
        daily.setDayStars(stars);
        daily.setDayStatus(stars >= 3 ? "IDEAL" : stars >= 2 ? "COMPLETED" : "NOT_COMPLETED");
        daily.setAggregationStatus(finalizeDay ? "VERIFIED" : "CALCULATED");
        daily.setFinalizedAt(finalizeDay ? LocalDateTime.now() : null);
        ManagerPerformanceDaily saved = dailyRepository.save(daily);
        if (finalizeDay && stars >= 2) {
            gamificationEventService.recordManagerMilestone(GamificationEventService.MANAGER_DAY_COMPLETED, manager,
                    date + ":completed", queueUntil, "{\"stars\":" + stars + "}");
            if (stars >= 3) gamificationEventService.recordManagerMilestone(GamificationEventService.MANAGER_IDEAL_DAY, manager,
                    date + ":ideal", queueUntil, "{\"stars\":3}");
        }
        return saved;
    }

    private TaskStats taskStats(List<ManagerDailyControlItem> items, List<ManagerDailyControlConcreteItem> concrete) {
        if (!concrete.isEmpty()) {
            long completed = concrete.stream().filter(item -> item.getStatus() != ManagerDailyControlItemStatus.OPEN).count();
            return new TaskStats(concrete.size(), completed, concrete.size() - completed);
        }
        List<ManagerDailyControlItem> actionItems = items.stream()
                .filter(item -> item.getGroup() == ManagerDailyControlGroup.ACTION)
                .toList();
        long total = actionItems.stream().mapToLong(ManagerDailyControlItem::getCount).sum();
        long completed = actionItems.stream()
                .filter(item -> item.getStatus() != ManagerDailyControlItemStatus.OPEN)
                .mapToLong(ManagerDailyControlItem::getCount)
                .sum();
        return new TaskStats(total, completed, Math.max(0, total - completed));
    }

    private ReplyStats replyStats(List<ClientChatMessage> messages) {
        Map<String, List<ClientChatMessage>> byChat = messages.stream()
                .filter(message -> message.getChatId() != null)
                .collect(Collectors.groupingBy(message -> message.getPlatform() + ":" + message.getChatId()));
        List<Long> allDurations = new ArrayList<>();
        List<Long> firstDurations = new ArrayList<>();
        for (List<ClientChatMessage> chatMessages : byChat.values()) {
            chatMessages.sort(Comparator.comparing(ClientChatMessage::getMessageAt).thenComparing(ClientChatMessage::getId));
            LocalDateTime waitingSince = null;
            boolean firstReplyCaptured = false;
            for (ClientChatMessage message : chatMessages) {
                if (message.getSenderRole() == ClientChatSenderRole.CLIENT && waitingSince == null) {
                    waitingSince = message.getMessageAt();
                    continue;
                }
                if (message.getSenderRole() == ClientChatSenderRole.STAFF && waitingSince != null) {
                    long seconds = businessSeconds(waitingSince, message.getMessageAt());
                    allDurations.add(seconds);
                    if (!firstReplyCaptured) {
                        firstDurations.add(seconds);
                        firstReplyCaptured = true;
                    }
                    waitingSince = null;
                }
            }
        }
        return new ReplyStats(distribution(firstDurations), distribution(allDurations));
    }

    private ProblemStats problemStats(List<ManagerDailyControlConcreteItem> items) {
        List<ManagerDailyControlConcreteItem> problems = items.stream()
                .filter(item -> !"CLIENT_CHAT_UNANSWERED".equals(item.getEntityType()))
                .filter(item -> !"RISK".equals(item.getEntityType()))
                .toList();
        List<Long> resolvedDurations = problems.stream()
                .filter(item -> item.getCreatedAt() != null && item.getResolvedAt() != null)
                .map(item -> Math.max(0, Duration.between(item.getCreatedAt(), item.getResolvedAt()).toSeconds()))
                .toList();
        long total = resolvedDurations.stream().mapToLong(Long::longValue).sum();
        return new ProblemStats(problems.size(), resolvedDurations.size(), total,
                resolvedDurations.isEmpty() ? 0 : Math.round(total / (double) resolvedDurations.size()));
    }

    private ActivityStats activityStats(Long managerId, LocalDateTime from, LocalDateTime to, List<ClientChatMessage> messages) {
        int idleMinutes = Math.max(1, appSettingService.getInt("manager.summary.activity-idle-minutes", 15));
        Duration idle = Duration.ofMinutes(idleMinutes);
        List<LocalDateTime> sitePoints = activityRepository
                .findByManager_IdAndOccurredAtBetweenOrderByOccurredAt(managerId, from, to).stream()
                .map(ManagerSiteActivityEvent::getOccurredAt).filter(Objects::nonNull).toList();
        List<LocalDateTime> messengerPoints = messages.stream()
                .filter(message -> message.getSenderRole() == ClientChatSenderRole.STAFF)
                .map(ClientChatMessage::getMessageAt).filter(Objects::nonNull).sorted().toList();
        List<Interval> site = sessions(sitePoints, idle, to);
        List<Interval> messenger = sessions(messengerPoints, idle, to);
        long siteSeconds = duration(site);
        long messengerSeconds = duration(messenger);
        long confirmedSeconds = duration(merge(StreamLists.concat(site, messenger)));
        long overlap = siteSeconds + messengerSeconds - confirmedSeconds;
        return new ActivityStats(siteSeconds, Math.max(0, messengerSeconds - overlap), confirmedSeconds);
    }

    private List<Interval> sessions(List<LocalDateTime> source, Duration idle, LocalDateTime limit) {
        List<LocalDateTime> points = source.stream().filter(Objects::nonNull).sorted().toList();
        if (points.isEmpty()) return List.of();
        List<Interval> intervals = new ArrayList<>();
        LocalDateTime start = points.getFirst();
        LocalDateTime last = start;
        for (int i = 1; i < points.size(); i++) {
            LocalDateTime point = points.get(i);
            if (Duration.between(last, point).compareTo(idle) > 0) {
                intervals.add(new Interval(start, min(last.plusSeconds(EVENT_TAIL_SECONDS), limit)));
                start = point;
            }
            last = point;
        }
        intervals.add(new Interval(start, min(last.plusSeconds(EVENT_TAIL_SECONDS), limit)));
        return merge(intervals);
    }

    private List<Interval> merge(List<Interval> source) {
        if (source.isEmpty()) return List.of();
        List<Interval> sorted = source.stream().sorted(Comparator.comparing(Interval::start)).toList();
        List<Interval> merged = new ArrayList<>();
        Interval current = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            Interval next = sorted.get(i);
            if (!next.start().isAfter(current.end())) {
                current = new Interval(current.start(), max(current.end(), next.end()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private long duration(List<Interval> intervals) {
        return intervals.stream().mapToLong(interval -> Math.max(0, Duration.between(interval.start(), interval.end()).toSeconds())).sum();
    }

    private Distribution distribution(List<Long> source) {
        List<Long> values = source.stream().sorted().toList();
        if (values.isEmpty()) return new Distribution(0, 0, 0, 0, 0, "0,0,0,0,0,0,0");
        long total = values.stream().mapToLong(Long::longValue).sum();
        long inSla = values.stream().filter(value -> value <= REPLY_SLA_SECONDS).count();
        long[] buckets = new long[7];
        for (long value : values) {
            int index = value <= 300 ? 0 : value <= 600 ? 1 : value <= 900 ? 2 : value <= 1800 ? 3
                    : value <= 3600 ? 4 : value <= 7200 ? 5 : 6;
            buckets[index]++;
        }
        return new Distribution(values.size(), total, Math.round(total / (double) values.size()),
                percentile(values, 0.50), percentile(values, 0.90), inSla,
                java.util.Arrays.stream(buckets).mapToObj(String::valueOf).collect(Collectors.joining(",")));
    }

    private long percentile(List<Long> values, double percentile) {
        int index = Math.max(0, Math.min(values.size() - 1, (int) Math.ceil(values.size() * percentile) - 1));
        return values.get(index);
    }

    private BigDecimal percent(long part, long total) {
        if (total <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(part * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }

    private String grade(int score) {
        return ManagerPerformanceGrade.of(score);
    }

    private long businessSeconds(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) return 0;
        // При свободном графике SLA идёт по реальному времени; отдых учитывается
        // отдельным резервом в суточном нормативе контроля очереди.
        return Duration.between(from, to).toSeconds();
    }

    private LocalTime parseTime(String value, LocalTime fallback) {
        try { return LocalTime.parse(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private void rebuildMonthly(LocalDate month, boolean closed) {
        LocalDate next = month.plusMonths(1);
        jdbcTemplate.update("""
            INSERT INTO manager_performance_monthly (
                month_start, manager_id, manager_user_id, working_days, score_sum, average_score,
                closing_score, closing_grade, task_total, task_completed, reply_count, reply_total_seconds,
                replies_in_sla, problem_count, problem_resolved_count, problem_resolution_total_seconds,
                site_active_seconds, messenger_active_seconds, confirmed_active_seconds, strong_days,
                controlled_seconds, clean_queue_seconds, completed_days, ideal_days, xp_earned,
                formula_version, closed_period
            )
            SELECT ?, d.manager_id, MAX(d.manager_user_id), COUNT(*), SUM(d.adjusted_score), AVG(d.adjusted_score),
                   SUBSTRING_INDEX(GROUP_CONCAT(d.adjusted_score ORDER BY d.summary_date DESC), ',', 1) + 0,
                   SUBSTRING_INDEX(GROUP_CONCAT(d.grade ORDER BY d.summary_date DESC), ',', 1),
                   SUM(d.task_total), SUM(d.task_completed), SUM(d.all_reply_count), SUM(d.all_reply_total_seconds),
                   SUM(d.replies_in_sla), SUM(d.problem_count), SUM(d.problem_resolved_count),
                   SUM(d.problem_resolution_total_seconds), SUM(d.site_active_seconds),
                   SUM(d.messenger_active_seconds), SUM(d.confirmed_active_seconds),
                   SUM(CASE WHEN d.adjusted_score >= 80 AND d.task_open = 0 THEN 1 ELSE 0 END),
                   SUM(d.controlled_seconds), SUM(d.clean_queue_seconds),
                   SUM(CASE WHEN d.day_status IN ('COMPLETED', 'IDEAL') THEN 1 ELSE 0 END),
                   SUM(CASE WHEN d.day_status = 'IDEAL' THEN 1 ELSE 0 END), SUM(d.xp_earned),
                   MAX(d.formula_version), ?
            FROM manager_performance_daily d
            WHERE d.summary_date >= ? AND d.summary_date < ?
            GROUP BY d.manager_id
            ON DUPLICATE KEY UPDATE
                working_days=VALUES(working_days), score_sum=VALUES(score_sum), average_score=VALUES(average_score),
                closing_score=VALUES(closing_score), closing_grade=VALUES(closing_grade),
                task_total=VALUES(task_total), task_completed=VALUES(task_completed), reply_count=VALUES(reply_count),
                reply_total_seconds=VALUES(reply_total_seconds), replies_in_sla=VALUES(replies_in_sla),
                problem_count=VALUES(problem_count), problem_resolved_count=VALUES(problem_resolved_count),
                problem_resolution_total_seconds=VALUES(problem_resolution_total_seconds),
                site_active_seconds=VALUES(site_active_seconds), messenger_active_seconds=VALUES(messenger_active_seconds),
                confirmed_active_seconds=VALUES(confirmed_active_seconds), strong_days=VALUES(strong_days),
                controlled_seconds=VALUES(controlled_seconds), clean_queue_seconds=VALUES(clean_queue_seconds),
                completed_days=VALUES(completed_days), ideal_days=VALUES(ideal_days), xp_earned=VALUES(xp_earned),
                formula_version=VALUES(formula_version), closed_period=VALUES(closed_period)
        """, month, closed, month, next);
        mergeHistograms(month, next, "manager_performance_monthly", "month_start", month);
    }

    private void rebuildYearly(LocalDate year, boolean closed) {
        LocalDate next = year.plusYears(1);
        jdbcTemplate.update("""
            INSERT INTO manager_performance_yearly (
                year_start, manager_id, manager_user_id, working_days, score_sum, average_score,
                closing_score, closing_grade, task_total, task_completed, reply_count, reply_total_seconds,
                replies_in_sla, problem_count, problem_resolved_count, problem_resolution_total_seconds,
                site_active_seconds, messenger_active_seconds, confirmed_active_seconds, strong_days,
                controlled_seconds, clean_queue_seconds, completed_days, ideal_days, xp_earned,
                formula_version, closed_period
            )
            SELECT ?, d.manager_id, MAX(d.manager_user_id), COUNT(*), SUM(d.adjusted_score), AVG(d.adjusted_score),
                   SUBSTRING_INDEX(GROUP_CONCAT(d.adjusted_score ORDER BY d.summary_date DESC), ',', 1) + 0,
                   SUBSTRING_INDEX(GROUP_CONCAT(d.grade ORDER BY d.summary_date DESC), ',', 1),
                   SUM(d.task_total), SUM(d.task_completed), SUM(d.all_reply_count), SUM(d.all_reply_total_seconds),
                   SUM(d.replies_in_sla), SUM(d.problem_count), SUM(d.problem_resolved_count),
                   SUM(d.problem_resolution_total_seconds), SUM(d.site_active_seconds),
                   SUM(d.messenger_active_seconds), SUM(d.confirmed_active_seconds),
                   SUM(CASE WHEN d.adjusted_score >= 80 AND d.task_open = 0 THEN 1 ELSE 0 END),
                   SUM(d.controlled_seconds), SUM(d.clean_queue_seconds),
                   SUM(CASE WHEN d.day_status IN ('COMPLETED', 'IDEAL') THEN 1 ELSE 0 END),
                   SUM(CASE WHEN d.day_status = 'IDEAL' THEN 1 ELSE 0 END), SUM(d.xp_earned),
                   MAX(d.formula_version), ?
            FROM manager_performance_daily d
            WHERE d.summary_date >= ? AND d.summary_date < ?
            GROUP BY d.manager_id
            ON DUPLICATE KEY UPDATE
                working_days=VALUES(working_days), score_sum=VALUES(score_sum), average_score=VALUES(average_score),
                closing_score=VALUES(closing_score), closing_grade=VALUES(closing_grade),
                task_total=VALUES(task_total), task_completed=VALUES(task_completed), reply_count=VALUES(reply_count),
                reply_total_seconds=VALUES(reply_total_seconds), replies_in_sla=VALUES(replies_in_sla),
                problem_count=VALUES(problem_count), problem_resolved_count=VALUES(problem_resolved_count),
                problem_resolution_total_seconds=VALUES(problem_resolution_total_seconds),
                site_active_seconds=VALUES(site_active_seconds), messenger_active_seconds=VALUES(messenger_active_seconds),
                confirmed_active_seconds=VALUES(confirmed_active_seconds), strong_days=VALUES(strong_days),
                controlled_seconds=VALUES(controlled_seconds), clean_queue_seconds=VALUES(clean_queue_seconds),
                completed_days=VALUES(completed_days), ideal_days=VALUES(ideal_days), xp_earned=VALUES(xp_earned),
                formula_version=VALUES(formula_version), closed_period=VALUES(closed_period)
        """, year, closed, year, next);
        mergeHistograms(year, next, "manager_performance_yearly", "year_start", year);
    }

    private void mergeHistograms(LocalDate from, LocalDate to, String table, String periodColumn, LocalDate period) {
        Map<Long, long[]> byManager = new HashMap<>();
        for (ManagerPerformanceDaily daily : dailyRepository.findBySummaryDateBetweenOrderByManager_IdAscSummaryDateAsc(from, to.minusDays(1))) {
            long[] total = byManager.computeIfAbsent(daily.getManager().getId(), ignored -> new long[7]);
            String[] values = daily.getReplyHistogram() == null ? new String[0] : daily.getReplyHistogram().split(",");
            for (int i = 0; i < Math.min(total.length, values.length); i++) {
                try {
                    total[i] += Long.parseLong(values[i]);
                } catch (NumberFormatException ignored) {
                    // A malformed historic bucket must not block the aggregate rebuild.
                }
            }
        }
        byManager.forEach((managerId, buckets) -> jdbcTemplate.update(
                "UPDATE " + table + " SET reply_histogram = ? WHERE " + periodColumn + " = ? AND manager_id = ?",
                java.util.Arrays.stream(buckets).mapToObj(String::valueOf).collect(Collectors.joining(",")),
                period,
                managerId
        ));
    }

    private ManagerDailySummaryResponse response(ManagerPerformanceDaily daily) {
        return new ManagerDailySummaryResponse(
                daily.getSummaryDate(), daily.getManager().getId(), daily.getManagerUserId(), daily.getManagerName(),
                daily.getAdjustedScore(), daily.getGrade(), daily.getTaskTotal(), daily.getTaskCompleted(), daily.getTaskOpen(),
                daily.getTaskProgressPercent(), daily.getOverdueCount(), daily.getRiskCount(), daily.getUnansweredCount(),
                daily.getFirstReplyAverageSeconds(), daily.getFirstReplyMedianSeconds(), daily.getAllReplyAverageSeconds(),
                daily.getAllReplyMedianSeconds(), daily.getAllReplyP90Seconds(), daily.getAllReplyCount(), daily.getRepliesInSla(),
                daily.getProblemCount(), daily.getProblemResolvedCount(), daily.getProblemResolutionAverageSeconds(),
                daily.getSiteActiveSeconds(), daily.getMessengerActiveSeconds(), daily.getConfirmedActiveSeconds(),
                daily.getLeadActionCount(), daily.getTargetSlaCount(), daily.getTargetSlaMetCount(), daily.getHardSlaBreachCount(),
                daily.getControlledSeconds(), daily.getCleanQueueSeconds(), daily.getDayStars(), daily.getDayStatus(), daily.getXpEarned(),
                daily.getAggregationStatus());
    }

    private LocalDateTime min(LocalDateTime left, LocalDateTime right) { return left.isBefore(right) ? left : right; }
    private LocalDateTime max(LocalDateTime left, LocalDateTime right) { return left.isAfter(right) ? left : right; }
    private long number(Long value) { return value == null ? 0L : value; }

    private record TaskStats(long total, long completed, long open) {}
    private record ReplyStats(Distribution first, Distribution all) {}
    private record Distribution(long count, long totalSeconds, long averageSeconds, long medianSeconds, long p90Seconds, long inSla, String histogram) {
        private Distribution(long count, long totalSeconds, long averageSeconds, long medianSeconds, long p90Seconds, String histogram) {
            this(count, totalSeconds, averageSeconds, medianSeconds, p90Seconds, 0, histogram);
        }
    }
    private record ProblemStats(long count, long resolvedCount, long totalResolutionSeconds, long averageResolutionSeconds) {}
    private record ActivityStats(long siteSeconds, long messengerOutsideSiteSeconds, long confirmedSeconds) {}
    private record Interval(LocalDateTime start, LocalDateTime end) {}

    private static final class StreamLists {
        private StreamLists() {}
        static <T> List<T> concat(List<T> first, List<T> second) {
            List<T> result = new ArrayList<>(first.size() + second.size());
            result.addAll(first);
            result.addAll(second);
            return result;
        }
    }
}
