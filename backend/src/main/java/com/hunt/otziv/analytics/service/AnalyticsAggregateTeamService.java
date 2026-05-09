package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.analytics.model.AnalyticsUserMetricAggregate;
import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateTeamService {

    private static final long DEFAULT_IMAGE_ID = 1L;

    private final AnalyticsAggregateReadService readService;

    public Optional<AggregateTeam> buildTeam(
            LocalDate selectedDate,
            List<Manager> managers,
            List<Marketolog> marketologs,
            List<Worker> workers,
            List<Operator> operators
    ) {
        if (selectedDate == null) {
            return Optional.empty();
        }

        LocalDate monthStart = selectedDate.withDayOfMonth(1);
        LocalDate monthEnd = selectedDate.withDayOfMonth(selectedDate.lengthOfMonth());
        List<Long> userIds = visibleUserIds(managers, marketologs, workers, operators);
        if (userIds.isEmpty()) {
            return Optional.of(new AggregateTeam(List.of(), List.of(), List.of(), List.of()));
        }

        List<? extends AnalyticsUserMetricAggregate> fullMonthRows = readService.monthlyUsers(userIds, monthStart, monthStart);
        if (fullMonthRows.isEmpty()) {
            fullMonthRows = readService.dailyUsers(userIds, monthStart, monthEnd);
        }

        List<AnalyticsUserMetricAggregate> partialPaymentRows = readService.dailyUsers(userIds, monthStart, selectedDate).stream()
                .map(AnalyticsUserMetricAggregate.class::cast)
                .toList();

        if (fullMonthRows.isEmpty() && partialPaymentRows.isEmpty()) {
            return Optional.empty();
        }

        Map<Long, TeamAccumulator> metrics = aggregateFullMonth(fullMonthRows);
        Map<Long, Long> managerPayments = aggregatePartialPayments(partialPaymentRows);

        return Optional.of(new AggregateTeam(
                mapManagers(managers, metrics, managerPayments),
                mapMarketologs(marketologs, metrics),
                mapWorkers(workers, metrics),
                mapOperators(operators, metrics)
        ));
    }

    private List<Long> visibleUserIds(
            List<Manager> managers,
            List<Marketolog> marketologs,
            List<Worker> workers,
            List<Operator> operators
    ) {
        return Stream.of(
                        userIds(managers, Manager::getUser),
                        userIds(marketologs, Marketolog::getUser),
                        userIds(workers, Worker::getUser),
                        userIds(operators, Operator::getUser)
                )
                .flatMap(Collection::stream)
                .distinct()
                .toList();
    }

    private <T> List<Long> userIds(List<T> entities, Function<T, User> userGetter) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
                .map(userGetter)
                .filter(user -> user != null && user.getId() != null)
                .map(User::getId)
                .toList();
    }

    private Map<Long, TeamAccumulator> aggregateFullMonth(List<? extends AnalyticsUserMetricAggregate> rows) {
        Map<Long, TeamAccumulator> metrics = new LinkedHashMap<>();
        for (AnalyticsUserMetricAggregate row : rows) {
            if (row.getUser() == null || row.getUser().getId() == null) {
                continue;
            }
            metrics.computeIfAbsent(row.getUser().getId(), ignored -> new TeamAccumulator())
                    .add(row);
        }
        return metrics;
    }

    private Map<Long, Long> aggregatePartialPayments(List<AnalyticsUserMetricAggregate> rows) {
        Map<Long, Long> payments = new LinkedHashMap<>();
        for (AnalyticsUserMetricAggregate row : rows) {
            if (row.getUser() == null || row.getUser().getId() == null) {
                continue;
            }
            payments.merge(row.getUser().getId(), toLong(row.getPaymentSum()), Long::sum);
        }
        return payments;
    }

    private List<ManagersListDTO> mapManagers(
            List<Manager> managers,
            Map<Long, TeamAccumulator> metrics,
            Map<Long, Long> managerPayments
    ) {
        return emptyIfNull(managers).stream()
                .map(manager -> {
                    User user = manager.getUser();
                    TeamAccumulator metric = metricFor(metrics, user);
                    return ManagersListDTO.builder()
                            .id(manager.getId())
                            .userId(userId(user))
                            .fio(fio(user))
                            .login(login(user))
                            .imageId(imageId(user))
                            .sum1Month(toInt(metric.salary))
                            .order1Month(toInt(metric.salaryEntries))
                            .review1Month(toInt(metric.salaryReviews))
                            .payment1Month(toInt(managerPayments.getOrDefault(userId(user), 0L)))
                            .leadsInWorkInMonth(toInt(metric.leadsInWork))
                            .build();
                })
                .toList();
    }

    private List<MarketologsListDTO> mapMarketologs(List<Marketolog> marketologs, Map<Long, TeamAccumulator> metrics) {
        return emptyIfNull(marketologs).stream()
                .map(marketolog -> {
                    User user = marketolog.getUser();
                    TeamAccumulator metric = metricFor(metrics, user);
                    return MarketologsListDTO.builder()
                            .id(marketolog.getId())
                            .userId(userId(user))
                            .fio(fio(user))
                            .login(login(user))
                            .imageId(imageId(user))
                            .sum1Month(toInt(metric.salary))
                            .order1Month(toInt(metric.salaryEntries))
                            .review1Month(toInt(metric.salaryReviews))
                            .leadsNew(metric.leadsNew)
                            .leadsInWork(metric.leadsInWork)
                            .percentInWork(percent(metric.leadsNew, metric.leadsInWork))
                            .build();
                })
                .toList();
    }

    private List<WorkersListDTO> mapWorkers(List<Worker> workers, Map<Long, TeamAccumulator> metrics) {
        return emptyIfNull(workers).stream()
                .map(worker -> {
                    User user = worker.getUser();
                    TeamAccumulator metric = metricFor(metrics, user);
                    return WorkersListDTO.builder()
                            .id(worker.getId())
                            .userId(userId(user))
                            .fio(fio(user))
                            .login(login(user))
                            .imageId(imageId(user))
                            .sum1Month(toInt(metric.salary))
                            .order1Month(toInt(metric.salaryEntries))
                            .review1Month(toInt(metric.salaryReviews))
                            .build();
                })
                .toList();
    }

    private List<OperatorsListDTO> mapOperators(List<Operator> operators, Map<Long, TeamAccumulator> metrics) {
        return emptyIfNull(operators).stream()
                .map(operator -> {
                    User user = operator.getUser();
                    TeamAccumulator metric = metricFor(metrics, user);
                    return OperatorsListDTO.builder()
                            .id(operator.getId())
                            .userId(userId(user))
                            .fio(fio(user))
                            .login(login(user))
                            .imageId(imageId(user))
                            .sum1Month(toInt(metric.salary))
                            .order1Month(toInt(metric.salaryEntries))
                            .review1Month(toInt(metric.salaryReviews))
                            .leadsNew(metric.leadsNew)
                            .leadsInWork(metric.leadsInWork)
                            .percentInWork(percent(metric.leadsNew, metric.leadsInWork))
                            .build();
                })
                .toList();
    }

    private <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : values;
    }

    private TeamAccumulator metricFor(Map<Long, TeamAccumulator> metrics, User user) {
        return user == null ? TeamAccumulator.EMPTY : metrics.getOrDefault(user.getId(), TeamAccumulator.EMPTY);
    }

    private static Long userId(User user) {
        return user == null ? null : user.getId();
    }

    private static String fio(User user) {
        return user == null ? null : user.getFio();
    }

    private static String login(User user) {
        return user == null ? null : user.getUsername();
    }

    private static Long imageId(User user) {
        Image image = user == null ? null : user.getImage();
        return image == null || image.getId() == null ? DEFAULT_IMAGE_ID : image.getId();
    }

    private static long toLong(BigDecimal value) {
        return value == null ? 0L : value.longValue();
    }

    private static int toInt(long value) {
        return Math.toIntExact(value);
    }

    private static Long percent(long total, long inWork) {
        return total == 0 ? 0L : (inWork * 100) / total;
    }

    private static class TeamAccumulator {
        private static final TeamAccumulator EMPTY = new TeamAccumulator();

        private long salary;
        private long salaryEntries;
        private long salaryReviews;
        private long leadsNew;
        private long leadsInWork;

        private void add(AnalyticsUserMetricAggregate row) {
            salary += toLong(row.getSalarySum());
            salaryEntries += row.getSalaryEntryCount();
            salaryReviews += row.getSalaryReviewCount();
            leadsNew += row.getLeadsNewCount();
            leadsInWork += row.getLeadsInWorkCount();
        }
    }

    public record AggregateTeam(
            List<ManagersListDTO> managers,
            List<MarketologsListDTO> marketologs,
            List<WorkersListDTO> workers,
            List<OperatorsListDTO> operators
    ) {
    }
}
