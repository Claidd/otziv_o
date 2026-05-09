package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService.AggregateTeam;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateTeamComparisonService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_OWNER = "ROLE_OWNER";
    private static final String GROUP_MANAGERS = "managers";
    private static final String GROUP_MARKETOLOGS = "marketologs";
    private static final String GROUP_WORKERS = "workers";
    private static final String GROUP_OPERATORS = "operators";

    private final PersonalService personalService;
    private final UserService userService;
    private final ManagerService managerService;
    private final MarketologService marketologService;
    private final WorkerService workerService;
    private final OperatorService operatorService;
    private final AnalyticsAggregateTeamService aggregateTeamService;

    public AnalyticsTeamComparison compare(String username, LocalDate selectedDate, String requestedRole) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username must not be blank");
        }
        if (selectedDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must not be null");
        }

        User user = userService.findByUserName(username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String role = resolveRole(user, requestedRole);

        TeamContext context = teamContext(username.trim(), selectedDate, role);
        if (context == null) {
            return unavailable(selectedDate, user, role, false, List.of());
        }

        Optional<AggregateTeam> aggregate = aggregateTeamService.buildTeam(
                selectedDate,
                context.managers(),
                context.marketologs(),
                context.workers(),
                context.operators()
        );
        if (aggregate.isEmpty()) {
            return unavailable(selectedDate, user, role, true, toRows(context.legacyTeam()));
        }

        List<TeamMetricRow> legacyRows = toRows(context.legacyTeam());
        List<TeamMetricRow> aggregateRows = toRows(aggregate.get());
        List<TeamFieldComparison> mismatches = compareRows(legacyRows, aggregateRows);

        return new AnalyticsTeamComparison(
                selectedDate,
                user.getUsername(),
                user.getId(),
                role,
                true,
                mismatches.isEmpty(),
                legacyRows.size(),
                aggregateRows.size(),
                comparedRowCount(legacyRows, aggregateRows),
                mismatches.size(),
                comparedFields(),
                skippedFields(),
                mismatches
        );
    }

    private AnalyticsTeamComparison unavailable(
            LocalDate selectedDate,
            User user,
            String role,
            boolean supportedRole,
            List<TeamMetricRow> legacyRows
    ) {
        return new AnalyticsTeamComparison(
                selectedDate,
                user.getUsername(),
                user.getId(),
                role,
                false,
                false,
                legacyRows.size(),
                0,
                0,
                0,
                supportedRole ? comparedFields() : Map.of(),
                supportedRole ? skippedFields() : Map.of(),
                List.of()
        );
    }

    private TeamContext teamContext(String username, LocalDate selectedDate, String role) {
        if (ROLE_ADMIN.equals(role)) {
            List<Manager> managers = managerService.getAllManagers();
            List<Marketolog> marketologs = marketologService.getAllMarketologs();
            List<Worker> workers = workerService.getAllWorkers();
            List<Operator> operators = operatorService.getAllOperators();
            AggregateTeam legacy = new AggregateTeam(
                    personalService.getManagersAndCountToDate(selectedDate),
                    personalService.getMarketologsAndCountToDate(selectedDate),
                    personalService.gerWorkersToAndCountToDate(selectedDate),
                    personalService.gerOperatorsAndCountToDate(selectedDate)
            );
            return new TeamContext(managers, marketologs, workers, operators, legacy);
        }

        if (ROLE_OWNER.equals(role)) {
            List<Manager> managers = userService.findManagersByUserName(username).stream().toList();
            List<Manager> expandedManagers = personalService.findAllManagersWorkers(managers);
            List<Marketolog> marketologs = expandedManagers.stream()
                    .map(Manager::getUser)
                    .filter(Objects::nonNull)
                    .flatMap(user -> emptyIfNull(user.getMarketologs()).stream())
                    .toList();
            List<Worker> workers = expandedManagers.stream()
                    .map(Manager::getUser)
                    .filter(Objects::nonNull)
                    .flatMap(user -> emptyIfNull(user.getWorkers()).stream())
                    .toList();
            List<Operator> operators = expandedManagers.stream()
                    .map(Manager::getUser)
                    .filter(Objects::nonNull)
                    .flatMap(user -> emptyIfNull(user.getOperators()).stream())
                    .toList();
            AggregateTeam legacy = new AggregateTeam(
                    castManagers(personalService.getManagersAndCountToDateToOwner(managers, selectedDate)),
                    castMarketologs(personalService.getMarketologsAndCountToDateToOwner(marketologs, selectedDate)),
                    castWorkers(personalService.gerWorkersToAndCountToDateToOwner(workers, selectedDate)),
                    castOperators(personalService.gerOperatorsAndCountToDateToOwner(operators, selectedDate))
            );
            return new TeamContext(managers, marketologs, workers, operators, legacy);
        }

        return null;
    }

    private String resolveRole(User user, String requestedRole) {
        if (requestedRole != null && !requestedRole.isBlank()) {
            String normalized = requestedRole.trim().toUpperCase();
            return normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
        }

        return user.getRoles().stream()
                .map(Role::getAuthority)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "User role not found"));
    }

    private List<TeamMetricRow> toRows(AggregateTeam team) {
        return Stream.of(
                        team.managers().stream().map(this::managerRow),
                        team.marketologs().stream().map(this::marketologRow),
                        team.workers().stream().map(this::workerRow),
                        team.operators().stream().map(this::operatorRow)
                )
                .flatMap(Function.identity())
                .toList();
    }

    private TeamMetricRow managerRow(ManagersListDTO manager) {
        return new TeamMetricRow(
                GROUP_MANAGERS,
                rowKey(GROUP_MANAGERS, manager.getUserId(), manager.getFio()),
                Map.of(
                        "sum1Month", (long) manager.getSum1Month(),
                        "order1Month", (long) manager.getOrder1Month(),
                        "review1Month", (long) manager.getReview1Month(),
                        "payment1Month", (long) manager.getPayment1Month()
                )
        );
    }

    private TeamMetricRow marketologRow(MarketologsListDTO marketolog) {
        return new TeamMetricRow(
                GROUP_MARKETOLOGS,
                rowKey(GROUP_MARKETOLOGS, marketolog.getUserId(), marketolog.getFio()),
                Map.of(
                        "sum1Month", (long) marketolog.getSum1Month(),
                        "order1Month", (long) marketolog.getOrder1Month(),
                        "review1Month", (long) marketolog.getReview1Month(),
                        "leadsNew", zeroIfNull(marketolog.getLeadsNew()),
                        "leadsInWork", zeroIfNull(marketolog.getLeadsInWork()),
                        "percentInWork", zeroIfNull(marketolog.getPercentInWork())
                )
        );
    }

    private TeamMetricRow workerRow(WorkersListDTO worker) {
        return new TeamMetricRow(
                GROUP_WORKERS,
                rowKey(GROUP_WORKERS, worker.getUserId(), worker.getFio()),
                Map.of(
                        "sum1Month", (long) worker.getSum1Month(),
                        "order1Month", (long) worker.getOrder1Month(),
                        "review1Month", (long) worker.getReview1Month()
                )
        );
    }

    private TeamMetricRow operatorRow(OperatorsListDTO operator) {
        return new TeamMetricRow(
                GROUP_OPERATORS,
                rowKey(GROUP_OPERATORS, operator.getUserId(), operator.getFio()),
                Map.of(
                        "sum1Month", (long) operator.getSum1Month(),
                        "order1Month", (long) operator.getOrder1Month(),
                        "review1Month", (long) operator.getReview1Month(),
                        "leadsNew", zeroIfNull(operator.getLeadsNew()),
                        "leadsInWork", zeroIfNull(operator.getLeadsInWork()),
                        "percentInWork", zeroIfNull(operator.getPercentInWork())
                )
        );
    }

    private List<TeamFieldComparison> compareRows(List<TeamMetricRow> legacyRows, List<TeamMetricRow> aggregateRows) {
        Map<String, TeamMetricRow> legacyByKey = byKey(legacyRows);
        Map<String, TeamMetricRow> aggregateByKey = byKey(aggregateRows);
        TreeSet<String> keys = new TreeSet<>(legacyByKey.keySet());
        keys.addAll(aggregateByKey.keySet());

        List<TeamFieldComparison> mismatches = new ArrayList<>();
        for (String key : keys) {
            TeamMetricRow legacy = legacyByKey.get(key);
            TeamMetricRow aggregate = aggregateByKey.get(key);
            if (legacy == null || aggregate == null) {
                mismatches.add(new TeamFieldComparison(
                        key,
                        "row",
                        presence(legacy),
                        presence(aggregate),
                        "",
                        false
                ));
                continue;
            }

            for (String field : legacy.values().keySet()) {
                Long legacyValue = legacy.values().get(field);
                Long aggregateValue = aggregate.values().getOrDefault(field, 0L);
                if (!legacyValue.equals(aggregateValue)) {
                    mismatches.add(new TeamFieldComparison(
                            key,
                            field,
                            String.valueOf(legacyValue),
                            String.valueOf(aggregateValue),
                            String.valueOf(aggregateValue - legacyValue),
                            false
                    ));
                }
            }
        }
        return mismatches;
    }

    private Map<String, TeamMetricRow> byKey(List<TeamMetricRow> rows) {
        return rows.stream()
                .collect(Collectors.toMap(
                        TeamMetricRow::key,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private int comparedRowCount(List<TeamMetricRow> legacyRows, List<TeamMetricRow> aggregateRows) {
        Set<String> aggregateKeys = aggregateRows.stream()
                .map(TeamMetricRow::key)
                .collect(Collectors.toSet());
        return (int) legacyRows.stream()
                .map(TeamMetricRow::key)
                .filter(aggregateKeys::contains)
                .count();
    }

    private String rowKey(String group, Long userId, String fio) {
        return group + "|" + (userId == null ? "fio:" + fio : "user:" + userId) + "|" + fio;
    }

    private Map<String, List<String>> comparedFields() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put(GROUP_MANAGERS, List.of("sum1Month", "order1Month", "review1Month", "payment1Month"));
        fields.put(GROUP_MARKETOLOGS, List.of("sum1Month", "order1Month", "review1Month", "leadsNew", "leadsInWork", "percentInWork"));
        fields.put(GROUP_WORKERS, List.of("sum1Month", "order1Month", "review1Month"));
        fields.put(GROUP_OPERATORS, List.of("sum1Month", "order1Month", "review1Month", "leadsNew", "leadsInWork", "percentInWork"));
        return fields;
    }

    private Map<String, List<String>> skippedFields() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put(GROUP_MANAGERS, List.of("id", "userId", "login", "fio", "imageId", "leadsInWorkInMonth"));
        fields.put(GROUP_MARKETOLOGS, List.of("id", "userId", "login", "fio", "imageId"));
        fields.put(GROUP_WORKERS, List.of("id", "userId", "login", "fio", "imageId", "newOrder", "inCorrect", "intVigul", "publish"));
        fields.put(GROUP_OPERATORS, List.of("id", "userId", "login", "fio", "imageId"));
        return fields;
    }

    private static String presence(TeamMetricRow row) {
        return row == null ? "missing" : "present";
    }

    private static Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }

    @SuppressWarnings("unchecked")
    private List<ManagersListDTO> castManagers(Object value) {
        return (List<ManagersListDTO>) value;
    }

    @SuppressWarnings("unchecked")
    private List<MarketologsListDTO> castMarketologs(Object value) {
        return (List<MarketologsListDTO>) value;
    }

    @SuppressWarnings("unchecked")
    private List<WorkersListDTO> castWorkers(Object value) {
        return (List<WorkersListDTO>) value;
    }

    @SuppressWarnings("unchecked")
    private List<OperatorsListDTO> castOperators(Object value) {
        return (List<OperatorsListDTO>) value;
    }

    private record TeamContext(
            List<Manager> managers,
            List<Marketolog> marketologs,
            List<Worker> workers,
            List<Operator> operators,
            AggregateTeam legacyTeam
    ) {
    }

    private record TeamMetricRow(String group, String key, Map<String, Long> values) {
    }

    public record AnalyticsTeamComparison(
            LocalDate date,
            String username,
            Long userId,
            String role,
            boolean aggregateAvailable,
            boolean matches,
            int legacyRowCount,
            int aggregateRowCount,
            int comparedRowCount,
            int mismatchCount,
            Map<String, List<String>> comparedFields,
            Map<String, List<String>> skippedFields,
            List<TeamFieldComparison> mismatches
    ) {
    }

    public record TeamFieldComparison(
            String rowKey,
            String field,
            String legacyValue,
            String aggregateValue,
            String delta,
            boolean matches
    ) {
    }
}
