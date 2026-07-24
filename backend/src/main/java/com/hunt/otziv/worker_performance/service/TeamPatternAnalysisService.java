package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.worker_performance.dto.TeamPatternAnalysisResponse;
import com.hunt.otziv.worker_performance.dto.TeamPatternAnalysisResponse.PatternInsight;
import com.hunt.otziv.worker_performance.dto.TeamPatternAnalysisResponse.WorkerPattern;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeamPatternAnalysisService {

    private static final ZoneId ANALYSIS_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final int MIN_WORKER_PUBLICATIONS = 30;
    private static final int MIN_CORRELATION_WORKERS = 8;
    private static final long MIN_TEAM_PUBLICATIONS = 200;
    private static final long MIN_TEMPORAL_GROUP_PUBLICATIONS = 30;
    private static final long MIN_PERSONAL_OUTCOMES = 3;

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public TeamPatternAnalysisResponse analyze(Collection<WorkerPatternSubject> subjects, LocalDate selectedMonth) {
        LocalDate monthStart = safeMonth(selectedMonth);
        LocalDate toExclusive = analysisEnd(monthStart);
        LocalDate dataThrough = toExclusive.minusDays(1);
        LocalDate analysisFrom = networkObservationStart(monthStart);
        Map<Long, WorkerPatternSubject> visibleByUserId = new LinkedHashMap<>();
        if (subjects != null) {
            subjects.stream()
                    .filter(Objects::nonNull)
                    .filter(subject -> subject.workerId() != null && subject.userId() != null)
                    .forEach(subject -> visibleByUserId.putIfAbsent(subject.userId(), subject));
        }
        List<WorkerPatternSubject> visible = List.copyOf(visibleByUserId.values());
        if (visible.isEmpty() || !toExclusive.isAfter(analysisFrom)) {
            return TeamPatternAnalysisResponse.empty(monthStart, dataThrough);
        }

        Map<Long, Long> userIdByWorkerId = new HashMap<>();
        visible.forEach(subject -> userIdByWorkerId.put(subject.workerId(), subject.userId()));
        Map<Long, WorkerMonth.Mutable> monthByUser = new LinkedHashMap<>();
        visible.forEach(subject -> monthByUser.put(subject.userId(), new WorkerMonth.Mutable(subject.userId())));
        Map<WorkerDayKey, WorkerDay.Mutable> days = new HashMap<>();

        loadPublications(visible, userIdByWorkerId, analysisFrom, toExclusive, monthByUser, days);
        loadBlockedAccounts(visible, analysisFrom, toExclusive, monthByUser, days);
        loadRecoveries(visible, userIdByWorkerId, analysisFrom, toExclusive, monthByUser, days);
        loadNetworkViolations(visible, analysisFrom, toExclusive, monthByUser, days);

        List<WorkerMonth> monthly = monthByUser.values().stream()
                .map(WorkerMonth.Mutable::toValue)
                .toList();
        List<WorkerMonth> comparable = monthly.stream()
                .filter(worker -> worker.publications() >= MIN_WORKER_PUBLICATIONS)
                .toList();
        long publications = monthly.stream().mapToLong(WorkerMonth::publications).sum();
        String confidence = teamConfidence(comparable.size(), publications, analysisFrom, dataThrough);
        if (comparable.size() < MIN_CORRELATION_WORKERS || publications < MIN_TEAM_PUBLICATIONS) {
            return insufficientResponse(analysisFrom, dataThrough, monthly, publications);
        }

        double medianBlocks = median(comparable.stream().map(WorkerMonth::blockRate).toList());
        double medianRecoveries = median(comparable.stream().map(WorkerMonth::recoveryRate).toList());
        double medianNetwork = median(comparable.stream().map(WorkerMonth::networkRate).toList());
        double networkBlockCorrelation = spearman(
                comparable.stream().map(WorkerMonth::networkRate).toList(),
                comparable.stream().map(WorkerMonth::blockRate).toList()
        );
        double networkRecoveryCorrelation = spearman(
                comparable.stream().map(WorkerMonth::networkRate).toList(),
                comparable.stream().map(WorkerMonth::recoveryRate).toList()
        );
        TemporalComparison sameDay = temporalComparison(
                visible, days, analysisFrom, toExclusive, 0, WorkerDay::blockedAccounts
        );
        TemporalComparison nextDay = temporalComparison(
                visible, days, analysisFrom, toExclusive, 1, WorkerDay::blockedAccounts
        );

        List<PatternInsight> teamInsights = new ArrayList<>();
        teamInsights.add(correlationInsight(networkBlockCorrelation, confidence, "NETWORK_BLOCKS",
                "Нарушения сети и блокировки", "блокировок"));
        teamInsights.add(temporalInsight(sameDay, nextDay, confidence));
        teamInsights.add(correlationInsight(networkRecoveryCorrelation, confidence, "NETWORK_RECOVERIES",
                "Нарушения сети и задачи восстановления", "созданных задач восстановления"));
        teamInsights.add(outlierSummary(comparable, medianBlocks, medianNetwork, confidence));

        Map<Long, WorkerPattern> workerPatterns = new LinkedHashMap<>();
        monthly.forEach(worker -> workerPatterns.put(worker.userId(), workerPattern(
                worker,
                medianBlocks,
                medianRecoveries,
                medianNetwork,
                personalTemporalComparison(
                        worker.userId(), days, analysisFrom, toExclusive, 0, WorkerDay::blockedAccounts
                ),
                personalTemporalComparison(
                        worker.userId(), days, analysisFrom, toExclusive, 1, WorkerDay::blockedAccounts
                ),
                personalTemporalComparison(
                        worker.userId(), days, analysisFrom, toExclusive, 0, WorkerDay::recoveries
                ),
                personalTemporalComparison(
                        worker.userId(), days, analysisFrom, toExclusive, 1, WorkerDay::recoveries
                ),
                true
        )));

        return new TeamPatternAnalysisResponse(
                true,
                analysisFrom,
                dataThrough,
                confidence,
                comparable.size(),
                publications,
                List.copyOf(teamInsights),
                Map.copyOf(workerPatterns)
        );
    }

    private LocalDate networkObservationStart(LocalDate monthStart) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT CASE
                           WHEN TIME(installed_on) = '00:00:00' THEN DATE(installed_on)
                           ELSE DATE(DATE_ADD(installed_on, INTERVAL 1 DAY))
                       END AS observation_start
                FROM flyway_schema_history
                WHERE version = '1.10.105'
                  AND success = 1
                ORDER BY installed_rank DESC
                LIMIT 1
                """, new MapSqlParameterSource());
        LocalDate observationStart = rows.isEmpty() ? null : dateValue(rows.getFirst().get("observation_start"));
        return observationStart != null && observationStart.isAfter(monthStart) ? observationStart : monthStart;
    }

    private void loadPublications(
            List<WorkerPatternSubject> subjects,
            Map<Long, Long> userIdByWorkerId,
            LocalDate from,
            LocalDate to,
            Map<Long, WorkerMonth.Mutable> monthByUser,
            Map<WorkerDayKey, WorkerDay.Mutable> days
    ) {
        MapSqlParameterSource params = baseParams(subjects, from, to).addValue(
                "workerIds",
                subjects.stream().map(WorkerPatternSubject::workerId).distinct().toList()
        );
        jdbc.queryForList("""
                SELECT r.review_worker AS worker_id,
                       DATE(COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed))) AS metric_date,
                       COUNT(*) AS metric_count
                FROM reviews r
                WHERE r.review_worker IN (:workerIds)
                  AND r.review_publish = 1
                  AND COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)) >= :from
                  AND COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)) < :to
                GROUP BY r.review_worker, DATE(COALESCE(r.review_published_marked_at, TIMESTAMP(r.review_changed)))
                """, params).forEach(row -> {
            Long userId = userIdByWorkerId.get(longValue(row.get("worker_id")));
            LocalDate date = dateValue(row.get("metric_date"));
            long count = longValue(row.get("metric_count"));
            if (userId == null || date == null) {
                return;
            }
            monthByUser.get(userId).publications += count;
            day(days, userId, date).publications += count;
        });
    }

    private void loadBlockedAccounts(
            List<WorkerPatternSubject> subjects,
            LocalDate from,
            LocalDate to,
            Map<Long, WorkerMonth.Mutable> monthByUser,
            Map<WorkerDayKey, WorkerDay.Mutable> days
    ) {
        MapSqlParameterSource params = baseParams(subjects, from, to);
        jdbc.queryForList("""
                SELECT e.worker_user_id AS user_id,
                       DATE(MIN(e.created_at)) AS metric_date,
                       SUBSTRING_INDEX(SUBSTRING_INDEX(e.details, 'botId=', -1), ';', 1) AS bot_id
                FROM worker_activity_events e
                WHERE e.worker_user_id IN (:userIds)
                  AND e.created_at >= :from
                  AND e.created_at < :to
                  AND e.action = 'REVIEW_BOT_DEACTIVATE'
                GROUP BY e.worker_user_id,
                         SUBSTRING_INDEX(SUBSTRING_INDEX(e.details, 'botId=', -1), ';', 1)
                """, params).forEach(row -> {
            Long userId = longValue(row.get("user_id"));
            LocalDate date = dateValue(row.get("metric_date"));
            if (!monthByUser.containsKey(userId) || date == null) {
                return;
            }
            monthByUser.get(userId).blockedAccounts++;
            day(days, userId, date).blockedAccounts++;
        });
    }

    private void loadRecoveries(
            List<WorkerPatternSubject> subjects,
            Map<Long, Long> userIdByWorkerId,
            LocalDate from,
            LocalDate to,
            Map<Long, WorkerMonth.Mutable> monthByUser,
            Map<WorkerDayKey, WorkerDay.Mutable> days
    ) {
        MapSqlParameterSource params = baseParams(subjects, from, to).addValue(
                "workerIds",
                subjects.stream().map(WorkerPatternSubject::workerId).distinct().toList()
        );
        jdbc.queryForList("""
                SELECT t.review_recovery_task_worker AS worker_id,
                       DATE(t.review_recovery_task_created_at) AS metric_date,
                       COUNT(*) AS metric_count
                FROM review_recovery_tasks t
                WHERE t.review_recovery_task_worker IN (:workerIds)
                  AND t.review_recovery_task_created_at >= :from
                  AND t.review_recovery_task_created_at < :to
                GROUP BY t.review_recovery_task_worker, DATE(t.review_recovery_task_created_at)
                """, params).forEach(row -> {
            Long userId = userIdByWorkerId.get(longValue(row.get("worker_id")));
            LocalDate date = dateValue(row.get("metric_date"));
            long count = longValue(row.get("metric_count"));
            if (userId != null && date != null) {
                monthByUser.get(userId).recoveries += count;
                day(days, userId, date).recoveries += count;
            }
        });
    }

    private void loadNetworkViolations(
            List<WorkerPatternSubject> subjects,
            LocalDate from,
            LocalDate to,
            Map<Long, WorkerMonth.Mutable> monthByUser,
            Map<WorkerDayKey, WorkerDay.Mutable> days
    ) {
        MapSqlParameterSource params = baseParams(subjects, from, to);
        jdbc.queryForList("""
                SELECT v.worker_user_id AS user_id,
                       DATE(GREATEST(v.first_seen_at, :from)) AS metric_date,
                       COUNT(*) AS metric_count,
                       SUM(v.attempt_count) AS attempt_count
                FROM worker_network_violation_episodes v
                WHERE v.worker_user_id IN (:userIds)
                  AND v.last_seen_at >= :from
                  AND v.first_seen_at < :to
                  AND v.access_result <> 'INVALIDATED'
                  AND v.reason_code IN ('NON_CELLULAR_NETWORK', 'VPN_PROXY_OR_DATACENTER')
                GROUP BY v.worker_user_id, DATE(GREATEST(v.first_seen_at, :from))
                """, params).forEach(row -> {
            Long userId = longValue(row.get("user_id"));
            LocalDate date = dateValue(row.get("metric_date"));
            long count = longValue(row.get("metric_count"));
            if (!monthByUser.containsKey(userId) || date == null) {
                return;
            }
            monthByUser.get(userId).networkEpisodes += count;
            monthByUser.get(userId).networkAttempts += longValue(row.get("attempt_count"));
            day(days, userId, date).networkEpisodes += count;
        });
    }

    private MapSqlParameterSource baseParams(List<WorkerPatternSubject> subjects, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("userIds", subjects.stream().map(WorkerPatternSubject::userId).distinct().toList())
                .addValue("from", from.atStartOfDay())
                .addValue("to", to.atStartOfDay());
    }

    private TemporalComparison temporalComparison(
            List<WorkerPatternSubject> subjects,
            Map<WorkerDayKey, WorkerDay.Mutable> days,
            LocalDate from,
            LocalDate to,
            int lagDays,
            ToLongFunction<WorkerDay> outcome
    ) {
        TemporalComparison.Mutable result = new TemporalComparison.Mutable();
        for (WorkerPatternSubject subject : subjects) {
            for (LocalDate date = from; date.isBefore(to); date = date.plusDays(1)) {
                if (date.minusDays(lagDays).isBefore(from)) {
                    continue;
                }
                WorkerDay current = dayValue(days, subject.userId(), date);
                WorkerDay classifier = dayValue(days, subject.userId(), date.minusDays(lagDays));
                result.add(classifier.networkEpisodes() > 0, current, outcome.applyAsLong(current));
            }
        }
        return result.toValue();
    }

    private TemporalComparison personalTemporalComparison(
            Long userId,
            Map<WorkerDayKey, WorkerDay.Mutable> days,
            LocalDate from,
            LocalDate to,
            int lagDays,
            ToLongFunction<WorkerDay> outcome
    ) {
        TemporalComparison.Mutable result = new TemporalComparison.Mutable();
        for (LocalDate date = from; date.isBefore(to); date = date.plusDays(1)) {
            if (date.minusDays(lagDays).isBefore(from)) {
                continue;
            }
            WorkerDay current = dayValue(days, userId, date);
            WorkerDay classifier = dayValue(days, userId, date.minusDays(lagDays));
            result.add(classifier.networkEpisodes() > 0, current, outcome.applyAsLong(current));
        }
        return result.toValue();
    }

    private PatternInsight correlationInsight(
            double correlation,
            String confidence,
            String code,
            String title,
            String target
    ) {
        double absolute = Math.abs(correlation);
        String strength = absolute >= 0.6 ? "заметная" : absolute >= 0.35 ? "умеренная" : absolute >= 0.2 ? "слабая" : "не обнаружена";
        String direction = correlation >= 0 ? "положительная" : "обратная";
        String message = absolute < 0.2
                ? "Устойчивой связи с уровнем %s не обнаружено (коэффициент %s).".formatted(target, format(correlation))
                : "%s %s связь с уровнем %s (коэффициент %s). Это наблюдение, а не доказательство причины."
                .formatted(capitalize(strength), direction, target, format(correlation));
        return new PatternInsight(code, absolute >= 0.6 ? "WARNING" : "INFO", confidence, title, message);
    }

    private PatternInsight temporalInsight(TemporalComparison sameDay, TemporalComparison nextDay, String confidence) {
        if (!sameDay.sufficient() || !nextDay.sufficient()) {
            return new PatternInsight(
                    "NETWORK_BLOCKS_TEMPORAL",
                    "NEUTRAL",
                    "INSUFFICIENT",
                    "Что происходит после нарушения",
                    "Пока недостаточно публикаций в сопоставимых днях, чтобы проверить изменение блокировок."
            );
        }
        double sameLift = sameDay.lift();
        double nextLift = nextDay.lift();
        boolean repeatedGrowth = sameLift >= 1.2 && nextLift >= 1.2;
        String message = repeatedGrowth
                ? "В дни нарушений и на следующий день блокировок больше обычного: %s и %s от базового уровня. Возможная связь требует проверки на следующих месяцах."
                .formatted(multiplier(sameLift), multiplier(nextLift))
                : "Рост блокировок после нарушения не подтверждён: в тот же день %s, на следующий — %s от обычного уровня."
                .formatted(multiplier(sameLift), multiplier(nextLift));
        return new PatternInsight(
                "NETWORK_BLOCKS_TEMPORAL",
                repeatedGrowth ? "WARNING" : "POSITIVE",
                confidence,
                "Что происходит после нарушения",
                message
        );
    }

    private PatternInsight personalTemporalInsight(
            String code,
            String title,
            String outcomeLabel,
            TemporalComparison sameDay,
            TemporalComparison nextDay,
            String confidence
    ) {
        boolean sameDayReady = sameDay.sufficient() && sameDay.outcomeCount() >= MIN_PERSONAL_OUTCOMES;
        boolean nextDayReady = nextDay.sufficient() && nextDay.outcomeCount() >= MIN_PERSONAL_OUTCOMES;
        if (!sameDayReady && !nextDayReady) {
            return new PatternInsight(
                    code,
                    "NEUTRAL",
                    "INSUFFICIENT",
                    title,
                    "Пока недостаточно дней с нарушениями и без них, публикаций или событий для персонального сравнения."
            );
        }

        boolean sameDayGrowth = sameDayReady && sameDay.lift() >= 1.3;
        boolean nextDayGrowth = nextDayReady && nextDay.lift() >= 1.3;
        boolean linkFound = sameDayGrowth || nextDayGrowth;
        String sameDayText = sameDayReady ? multiplier(sameDay.lift()) : "недостаточно данных";
        String nextDayText = nextDayReady ? multiplier(nextDay.lift()) : "недостаточно данных";
        String message = linkFound
                ? "В дни нарушений уровень %s составляет %s от обычного, на следующий день — %s. Обнаружена временная связь, но она не доказывает причину."
                .formatted(outcomeLabel, sameDayText, nextDayText)
                : "Рост %s после нарушений не подтверждён: в тот же день %s, на следующий — %s от обычного уровня."
                .formatted(outcomeLabel, sameDayText, nextDayText);
        return new PatternInsight(
                code,
                linkFound ? "WARNING" : "INFO",
                confidence,
                title,
                message
        );
    }

    private PatternInsight outlierSummary(
            List<WorkerMonth> workers,
            double medianBlocks,
            double medianNetwork,
            String confidence
    ) {
        long simultaneous = workers.stream()
                .filter(worker -> isElevated(worker.blockRate(), medianBlocks) && isElevated(worker.networkRate(), medianNetwork))
                .count();
        String message = simultaneous == 0
                ? "Нет работников, у которых одновременно заметно превышены блокировки и нарушения сети."
                : "У %d сотрудников одновременно повышены блокировки и нарушения сети. Это сигнал для проверки, но не вывод о причине."
                .formatted(simultaneous);
        return new PatternInsight(
                "COMBINED_OUTLIERS",
                simultaneous > 0 ? "WARNING" : "POSITIVE",
                confidence,
                "Одновременные отклонения",
                message
        );
    }

    private WorkerPattern workerPattern(
            WorkerMonth worker,
            double medianBlocks,
            double medianRecoveries,
            double medianNetwork,
            TemporalComparison blockSameDay,
            TemporalComparison blockNextDay,
            TemporalComparison recoverySameDay,
            TemporalComparison recoveryNextDay,
            boolean teamComparisonAvailable
    ) {
        String confidence = !teamComparisonAvailable || worker.publications() < MIN_WORKER_PUBLICATIONS
                ? "INSUFFICIENT"
                : worker.publications() < 80 || worker.networkEpisodes() < 3 ? "LIMITED" : "MODERATE";
        List<PatternInsight> insights = new ArrayList<>();
        if (worker.publications() < MIN_WORKER_PUBLICATIONS) {
            insights.add(new PatternInsight(
                    "WORKER_NOT_ENOUGH_DATA",
                    "NEUTRAL",
                    "INSUFFICIENT",
                    "Недостаточно данных",
                    "Меньше %d публикаций — персональные отклонения пока ненадёжны.".formatted(MIN_WORKER_PUBLICATIONS)
            ));
        } else if (!teamComparisonAvailable) {
            insights.add(new PatternInsight(
                    "WORKER_TEAM_NOT_ENOUGH_DATA",
                    "NEUTRAL",
                    "INSUFFICIENT",
                    "Недостаточно данных команды",
                    "Пока недостаточно сопоставимых сотрудников для надёжного сравнения с медианой команды."
            ));
        } else {
            boolean highBlocks = isElevated(worker.blockRate(), medianBlocks);
            boolean highRecoveries = worker.recoveries() >= MIN_PERSONAL_OUTCOMES
                    && isElevated(worker.recoveryRate(), medianRecoveries);
            boolean highNetwork = worker.networkEpisodes() >= 3 && isElevated(worker.networkRate(), medianNetwork);
            if (highBlocks) {
                insights.add(new PatternInsight(
                        "WORKER_BLOCK_RATE_HIGH",
                        "WARNING",
                        confidence,
                        "Блокировки выше команды",
                        "%s на 100 публикаций против медианы команды %s."
                                .formatted(format(worker.blockRate()), format(medianBlocks))
                ));
            }
            if (highNetwork) {
                insights.add(new PatternInsight(
                        "WORKER_NETWORK_RATE_HIGH",
                        "WARNING",
                        confidence,
                        "Нарушения сети выше команды",
                        "%s эпизода на 100 публикаций против медианы %s."
                                .formatted(format(worker.networkRate()), format(medianNetwork))
                ));
            }
            if (highRecoveries) {
                insights.add(new PatternInsight(
                        "WORKER_RECOVERY_RATE_HIGH",
                        "WARNING",
                        confidence,
                        "Восстановления выше команды",
                        "%s созданных задач на 100 публикаций против медианы команды %s."
                                .formatted(format(worker.recoveryRate()), format(medianRecoveries))
                ));
            }
            if (highNetwork && (highBlocks || highRecoveries)) {
                insights.add(new PatternInsight(
                        "WORKER_COMBINED_HIGH",
                        "INFO",
                        confidence,
                        "Показатели повышены одновременно",
                        "Нарушения сети повышены одновременно с блокировками или восстановлениями. Это сигнал для проверки по времени, а не доказательство причины."
                ));
            }
            insights.add(personalTemporalInsight(
                    "WORKER_NETWORK_BLOCK_PATTERN",
                    "Нарушения сети и блокировки",
                    "блокировок",
                    blockSameDay,
                    blockNextDay,
                    confidence
            ));
            insights.add(personalTemporalInsight(
                    "WORKER_NETWORK_RECOVERY_PATTERN",
                    "Нарушения сети и восстановления",
                    "созданных задач восстановления",
                    recoverySameDay,
                    recoveryNextDay,
                    confidence
            ));
        }
        return new WorkerPattern(
                worker.userId(),
                worker.publications(),
                worker.blockedAccounts(),
                worker.recoveries(),
                worker.networkEpisodes(),
                round1(worker.blockRate()),
                round1(worker.recoveryRate()),
                round1(worker.networkRate()),
                round1(medianBlocks),
                round1(medianRecoveries),
                round1(medianNetwork),
                confidence,
                List.copyOf(insights)
        );
    }

    private TeamPatternAnalysisResponse insufficientResponse(
            LocalDate from,
            LocalDate to,
            List<WorkerMonth> workers,
            long publications
    ) {
        Map<Long, WorkerPattern> workerPatterns = new LinkedHashMap<>();
        workers.forEach(worker -> workerPatterns.put(worker.userId(), workerPattern(
                worker, 0, 0, 0,
                TemporalComparison.empty(), TemporalComparison.empty(),
                TemporalComparison.empty(), TemporalComparison.empty(),
                false
        )));
        return new TeamPatternAnalysisResponse(
                true,
                from,
                to,
                "INSUFFICIENT",
                (int) workers.stream().filter(worker -> worker.publications() >= MIN_WORKER_PUBLICATIONS).count(),
                publications,
                List.of(new PatternInsight(
                        "NOT_ENOUGH_DATA",
                        "NEUTRAL",
                        "INSUFFICIENT",
                        "Недостаточно данных",
                        "Нужно минимум %d работников с %d публикациями и не менее %d публикаций команды."
                                .formatted(MIN_CORRELATION_WORKERS, MIN_WORKER_PUBLICATIONS, MIN_TEAM_PUBLICATIONS)
                )),
                Map.copyOf(workerPatterns)
        );
    }

    private String teamConfidence(int workers, long publications, LocalDate from, LocalDate to) {
        long days = Math.max(0, ChronoUnit.DAYS.between(from, to.plusDays(1)));
        if (workers < MIN_CORRELATION_WORKERS || publications < MIN_TEAM_PUBLICATIONS) {
            return "INSUFFICIENT";
        }
        return workers >= 12 && publications >= 500 && days >= 14 ? "MODERATE" : "LIMITED";
    }

    static double spearman(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.size() != right.size() || left.size() < 2) {
            return 0;
        }
        return pearson(ranks(left), ranks(right));
    }

    private static List<Double> ranks(List<Double> values) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            indexes.add(index);
        }
        indexes.sort(Comparator.comparingDouble(values::get));
        double[] ranks = new double[values.size()];
        int start = 0;
        while (start < indexes.size()) {
            int end = start + 1;
            double value = values.get(indexes.get(start));
            while (end < indexes.size() && Double.compare(values.get(indexes.get(end)), value) == 0) {
                end++;
            }
            double rank = ((start + 1) + end) / 2.0;
            for (int position = start; position < end; position++) {
                ranks[indexes.get(position)] = rank;
            }
            start = end;
        }
        List<Double> result = new ArrayList<>(ranks.length);
        for (double rank : ranks) {
            result.add(rank);
        }
        return result;
    }

    private static double pearson(List<Double> left, List<Double> right) {
        double leftMean = left.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double rightMean = right.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double numerator = 0;
        double leftSquare = 0;
        double rightSquare = 0;
        for (int index = 0; index < left.size(); index++) {
            double leftDelta = left.get(index) - leftMean;
            double rightDelta = right.get(index) - rightMean;
            numerator += leftDelta * rightDelta;
            leftSquare += leftDelta * leftDelta;
            rightSquare += rightDelta * rightDelta;
        }
        double denominator = Math.sqrt(leftSquare * rightSquare);
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static WorkerDay.Mutable day(Map<WorkerDayKey, WorkerDay.Mutable> days, Long userId, LocalDate date) {
        return days.computeIfAbsent(new WorkerDayKey(userId, date), ignored -> new WorkerDay.Mutable());
    }

    private static WorkerDay dayValue(Map<WorkerDayKey, WorkerDay.Mutable> days, Long userId, LocalDate date) {
        WorkerDay.Mutable mutable = days.get(new WorkerDayKey(userId, date));
        return mutable == null ? WorkerDay.empty() : mutable.toValue();
    }

    private static boolean isElevated(double value, double median) {
        return value >= median + 10 && (median <= 0 ? value >= 10 : value >= median * 1.35);
    }

    private static double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
    }

    private static String format(double value) {
        return String.format(Locale.forLanguageTag("ru-RU"), "%.1f", value);
    }

    private static String multiplier(double value) {
        return String.format(Locale.forLanguageTag("ru-RU"), "%.2f×", value);
    }

    private static String capitalize(String value) {
        return value == null || value.isBlank()
                ? ""
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static LocalDate safeMonth(LocalDate date) {
        return (date == null ? LocalDate.now(ANALYSIS_ZONE) : date).withDayOfMonth(1);
    }

    private static LocalDate analysisEnd(LocalDate monthStart) {
        LocalDate nextMonth = monthStart.plusMonths(1);
        LocalDate tomorrow = LocalDate.now(ANALYSIS_ZONE).plusDays(1);
        return nextMonth.isBefore(tomorrow) ? nextMonth : tomorrow;
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static LocalDate dateValue(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return value == null ? null : LocalDate.parse(value.toString());
    }

    public record WorkerPatternSubject(Long workerId, Long userId, String name) {
    }

    private record WorkerDayKey(Long userId, LocalDate date) {
    }

    private record WorkerDay(long publications, long blockedAccounts, long recoveries, long networkEpisodes) {
        static WorkerDay empty() {
            return new WorkerDay(0, 0, 0, 0);
        }

        private static final class Mutable {
            private long publications;
            private long blockedAccounts;
            private long recoveries;
            private long networkEpisodes;

            private WorkerDay toValue() {
                return new WorkerDay(publications, blockedAccounts, recoveries, networkEpisodes);
            }
        }
    }

    private record WorkerMonth(
            Long userId,
            long publications,
            long blockedAccounts,
            long recoveries,
            long networkEpisodes,
            long networkAttempts
    ) {
        double blockRate() {
            return rate(blockedAccounts, publications);
        }

        double recoveryRate() {
            return rate(recoveries, publications);
        }

        double networkRate() {
            return rate(networkEpisodes, publications);
        }

        private static double rate(long count, long publications) {
            return publications <= 0 ? 0 : count * 100.0 / publications;
        }

        private static final class Mutable {
            private final Long userId;
            private long publications;
            private long blockedAccounts;
            private long recoveries;
            private long networkEpisodes;
            private long networkAttempts;

            private Mutable(Long userId) {
                this.userId = userId;
            }

            private WorkerMonth toValue() {
                return new WorkerMonth(userId, publications, blockedAccounts, recoveries, networkEpisodes, networkAttempts);
            }
        }
    }

    private record TemporalComparison(
            long violationPublications,
            long violationOutcomes,
            long cleanPublications,
            long cleanOutcomes
    ) {
        static TemporalComparison empty() {
            return new TemporalComparison(0, 0, 0, 0);
        }

        boolean sufficient() {
            return violationPublications >= MIN_TEMPORAL_GROUP_PUBLICATIONS
                    && cleanPublications >= MIN_TEMPORAL_GROUP_PUBLICATIONS;
        }

        double lift() {
            double cleanRate = cleanPublications <= 0 ? 0 : cleanOutcomes * 1.0 / cleanPublications;
            double violationRate = violationPublications <= 0 ? 0 : violationOutcomes * 1.0 / violationPublications;
            if (cleanRate == 0) {
                return violationRate == 0 ? 1 : 2;
            }
            return violationRate / cleanRate;
        }

        long outcomeCount() {
            return violationOutcomes + cleanOutcomes;
        }

        private static final class Mutable {
            private long violationPublications;
            private long violationOutcomes;
            private long cleanPublications;
            private long cleanOutcomes;

            private void add(boolean violation, WorkerDay day, long outcomes) {
                if (violation) {
                    violationPublications += day.publications();
                    violationOutcomes += outcomes;
                } else {
                    cleanPublications += day.publications();
                    cleanOutcomes += outcomes;
                }
            }

            private TemporalComparison toValue() {
                return new TemporalComparison(
                        violationPublications,
                        violationOutcomes,
                        cleanPublications,
                        cleanOutcomes
                );
            }
        }
    }
}
