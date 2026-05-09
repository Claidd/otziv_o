package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.UserData;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateScoreService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateStatsService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateUserStatsService;
import com.hunt.otziv.config.cache.CacheConfig;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cabinet")
public class ApiCabinetController {

    private static final List<String> BUSINESS_ROLE_PRIORITY = List.of(
            "ROLE_ADMIN",
            "ROLE_OWNER",
            "ROLE_MANAGER",
            "ROLE_WORKER",
            "ROLE_OPERATOR",
            "ROLE_MARKETOLOG"
    );

    private final PersonalService personalService;
    private final UserService userService;
    private final ManagerService managerService;
    private final PerformanceMetrics performanceMetrics;
    private final CacheManager cacheManager;
    private final AnalyticsAggregateStatsService analyticsAggregateStatsService;
    private final AnalyticsAggregateScoreService analyticsAggregateScoreService;
    private final AnalyticsAggregateUserStatsService analyticsAggregateUserStatsService;
    private final AnalyticsAggregateTeamService analyticsAggregateTeamService;

    @Value("${otziv.analytics.aggregates.read-enabled:false}")
    private boolean aggregateAnalyticsReadEnabled;

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public CabinetProfileResponse profile(
            Principal principal,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh
    ) {
        return performanceMetrics.recordEndpoint("cabinet.profile", () -> {
            LocalDate selectedDate = selectedDate(date);

            return cached(
                    CacheConfig.CABINET_PROFILE,
                    cabinetKey("profile", principal.getName(), selectedDate, aggregateAnalyticsReadEnabled),
                    refresh,
                    () -> {
                        User user = currentUser(principal);
                        return new CabinetProfileResponse(
                                selectedDate,
                                personalService.getUserLK(principal),
                                workerStats(selectedDate, user)
                        );
                    }
            );
        });
    }

    @GetMapping("/user-info")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public CabinetUserInfoResponse userInfo(
            Principal principal,
            @RequestParam("userId") Long userId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh
    ) {
        return performanceMetrics.recordEndpoint("cabinet.user_info", () -> {
            LocalDate selectedDate = selectedDate(date);

            return cached(
                    CacheConfig.CABINET_USER_INFO,
                    cabinetKey("user-info", principal.getName(), userId, selectedDate, aggregateAnalyticsReadEnabled),
                    refresh,
                    () -> {
                        User user = userService.findByIdToUserInfo(userId);
                        if (user == null) {
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
                        }

                        return new CabinetUserInfoResponse(
                                selectedDate,
                                personalService.getUserLK(principal),
                                workerStats(selectedDate, user)
                        );
                    }
            );
        });
    }

    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public TeamResponse team(
            Principal principal,
            Authentication authentication,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh
    ) {
        return performanceMetrics.recordEndpoint("cabinet.team", () -> {
            LocalDate selectedDate = selectedDate(date);
            String role = primaryRole(authentication);

            return cached(
                    CacheConfig.CABINET_TEAM,
                    cabinetKey("team", principal.getName(), role, selectedDate, aggregateAnalyticsReadEnabled),
                    refresh,
                    () -> {
                        User user = currentUser(principal);
                        boolean canManageUsers = hasAnyRole(authentication, "ROLE_ADMIN", "ROLE_OWNER");

                        if ("ROLE_MANAGER".equals(role)) {
                            Manager manager = managerService.getManagerByUserId(user.getId());
                            return new TeamResponse(
                                    selectedDate,
                                    shortRole(role),
                                    canManageUsers,
                                    false,
                                    false,
                                    List.of(),
                                    personalService.getMarketologsToManager(manager),
                                    personalService.gerWorkersToManager(manager),
                                    personalService.gerOperatorsToManager(manager)
                            );
                        }

                        if ("ROLE_OWNER".equals(role)) {
                            List<Manager> managers = userService.findManagersByUserName(principal.getName()).stream().toList();
                            List<Manager> expandedManagers = personalService.findAllManagersWorkers(managers);
                            List<Marketolog> marketologs = expandedManagers.stream()
                                    .flatMap(manager -> manager.getUser().getMarketologs().stream())
                                    .toList();
                            List<Operator> operators = expandedManagers.stream()
                                    .flatMap(manager -> manager.getUser().getOperators().stream())
                                    .toList();
                            List<Worker> workers = expandedManagers.stream()
                                    .flatMap(manager -> manager.getUser().getWorkers().stream())
                                    .toList();

                            return ownerTeamResponse(
                                    selectedDate,
                                    role,
                                    managers,
                                    marketologs,
                                    workers,
                                    operators
                            );
                        }

                        return new TeamResponse(
                                selectedDate,
                                shortRole(role),
                                canManageUsers,
                                canManageUsers,
                                true,
                                personalService.getManagers(),
                                personalService.getMarketologs(),
                                personalService.gerWorkers(),
                                personalService.gerOperators()
                        );
                    }
            );
        });
    }

    @GetMapping("/score")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG')")
    public ScoreResponse score(
            Principal principal,
            Authentication authentication,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh
    ) {
        return performanceMetrics.recordEndpoint("cabinet.score", () -> {
            LocalDate selectedDate = selectedDate(date);
            boolean financeVisible = hasAnyRole(authentication, "ROLE_ADMIN", "ROLE_OWNER");

            return cached(
                    CacheConfig.CABINET_SCORE,
                    cabinetKey("score", principal.getName(), financeVisible, selectedDate, aggregateAnalyticsReadEnabled),
                    refresh,
                    () -> {
                        Map<String, List<ScoreUserResponse>> groupedUsers = scoreRows(selectedDate).stream()
                                .sorted(scoreComparator(financeVisible))
                                .map(user -> ScoreUserResponse.from(user, financeVisible))
                                .collect(Collectors.groupingBy(
                                        ScoreUserResponse::role,
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                ));

                        return new ScoreResponse(
                                selectedDate,
                                personalService.getUserLK(principal),
                                financeVisible,
                                Map.of(
                                        "managers", groupedUsers.getOrDefault("ROLE_MANAGER", List.of()),
                                        "marketologs", groupedUsers.getOrDefault("ROLE_MARKETOLOG", List.of()),
                                        "workers", groupedUsers.getOrDefault("ROLE_WORKER", List.of()),
                                        "operators", groupedUsers.getOrDefault("ROLE_OPERATOR", List.of())
                                )
                        );
                    }
            );
        });
    }

    @GetMapping("/analyse")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public AnalyticsResponse analyse(
            Principal principal,
            Authentication authentication,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(value = "allTime", defaultValue = "false") boolean allTime,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh
    ) {
        return performanceMetrics.recordEndpoint("cabinet.analyse", () -> {
            LocalDate selectedDate = selectedDate(date);
            String role = primaryRole(authentication);
            AnalyticsPeriod period = analyticsPeriod(selectedDate, from, to, allTime);

            return cached(
                    CacheConfig.CABINET_ANALYTICS,
                    cabinetKey(
                            "analytics",
                            principal.getName(),
                            role,
                            selectedDate,
                            period.from(),
                            period.to(),
                            period.allTime(),
                            aggregateAnalyticsReadEnabled
                    ),
                    refresh,
                    () -> {
                        User user = currentUser(principal);
                        if (refresh) {
                            evictCache(CacheConfig.CABINET_STATS, statsKey(selectedDate, user, role));
                        }

                        return new AnalyticsResponse(
                                selectedDate,
                                new AnalyticsPeriodResponse(period.from(), period.to(), period.allTime()),
                                personalService.getUserLK(principal),
                                stats(selectedDate, user, role, period)
                        );
                    }
            );
        });
    }

    private LocalDate selectedDate(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }

    private <T> T cached(String cacheName, String key, boolean refresh, Supplier<T> valueLoader) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return valueLoader.get();
        }

        if (refresh) {
            cache.evict(key);
        }

        return cache.get(key, valueLoader::get);
    }

    private void evictCache(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    private String cabinetKey(Object... parts) {
        return java.util.Arrays.stream(parts)
                .map(String::valueOf)
                .collect(Collectors.joining(":"));
    }

    private String statsKey(LocalDate selectedDate, User user, String role) {
        return cabinetKey(selectedDate, user.getId(), role);
    }

    private AnalyticsPeriod analyticsPeriod(LocalDate selectedDate, LocalDate from, LocalDate to, boolean allTime) {
        LocalDate resolvedFrom = allTime ? AnalyticsAggregateStatsService.allTimeChartFrom() : from;
        LocalDate resolvedTo = allTime ? selectedDate : to;
        if (resolvedFrom == null) {
            resolvedFrom = AnalyticsAggregateStatsService.defaultChartFrom(selectedDate);
        }
        if (resolvedTo == null) {
            resolvedTo = selectedDate;
        }
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before or equal to to");
        }
        if (resolvedTo.isAfter(selectedDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must not be after date");
        }
        return new AnalyticsPeriod(resolvedFrom, resolvedTo, allTime);
    }

    private StatDTO stats(LocalDate selectedDate, User user, String role, AnalyticsPeriod period) {
        if (!aggregateAnalyticsReadEnabled) {
            return personalService.getStats(selectedDate, user, role);
        }
        return analyticsAggregateStatsService.buildStats(selectedDate, user, role, period.from(), period.to())
                .orElseGet(() -> personalService.getStats(selectedDate, user, role));
    }

    private List<UserData> scoreRows(LocalDate selectedDate) {
        if (!aggregateAnalyticsReadEnabled) {
            return personalService.getPersonalsAndCountToScore(selectedDate);
        }
        return analyticsAggregateScoreService.buildScore(selectedDate)
                .orElseGet(() -> personalService.getPersonalsAndCountToScore(selectedDate));
    }

    private TeamResponse ownerTeamResponse(
            LocalDate selectedDate,
            String role,
            List<Manager> managers,
            List<Marketolog> marketologs,
            List<Worker> workers,
            List<Operator> operators
    ) {
        if (aggregateAnalyticsReadEnabled) {
            return analyticsAggregateTeamService.buildTeam(selectedDate, managers, marketologs, workers, operators)
                    .map(team -> new TeamResponse(
                            selectedDate,
                            shortRole(role),
                            true,
                            true,
                            true,
                            team.managers(),
                            team.marketologs(),
                            team.workers(),
                            team.operators()
                    ))
                    .orElseGet(() -> ownerLegacyTeamResponse(selectedDate, role, managers, marketologs, workers, operators));
        }

        return ownerLegacyTeamResponse(selectedDate, role, managers, marketologs, workers, operators);
    }

    private TeamResponse ownerLegacyTeamResponse(
            LocalDate selectedDate,
            String role,
            List<Manager> managers,
            List<Marketolog> marketologs,
            List<Worker> workers,
            List<Operator> operators
    ) {
        return new TeamResponse(
                selectedDate,
                shortRole(role),
                true,
                true,
                true,
                managersToOwner(managers, selectedDate),
                marketologsToOwner(marketologs, selectedDate),
                workersToOwner(workers, selectedDate),
                operatorsToOwner(operators, selectedDate)
        );
    }

    private UserStatDTO workerStats(LocalDate selectedDate, User user) {
        if (!aggregateAnalyticsReadEnabled) {
            return personalService.getWorkerReviews(user, selectedDate);
        }
        return analyticsAggregateUserStatsService.buildUserStats(selectedDate, user)
                .orElseGet(() -> personalService.getWorkerReviews(user, selectedDate));
    }

    private User currentUser(Principal principal) {
        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private String primaryRole(Authentication authentication) {
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return BUSINESS_ROLE_PRIORITY.stream()
                .filter(authorities::contains)
                .findFirst()
                .orElseGet(() -> authorities.stream()
                        .filter(authority -> authority.startsWith("ROLE_"))
                        .findFirst()
                        .orElse("ROLE_USER"));
    }

    private String shortRole(String role) {
        return role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
    }

    private boolean hasAnyRole(Authentication authentication, String... roles) {
        Collection<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        for (String role : roles) {
            if (authorities.contains(role)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<ManagersListDTO> managersToOwner(List<Manager> managers, LocalDate date) {
        return (List<ManagersListDTO>) personalService.getManagersAndCountToDateToOwner(managers, date);
    }

    @SuppressWarnings("unchecked")
    private List<MarketologsListDTO> marketologsToOwner(List<Marketolog> marketologs, LocalDate date) {
        return (List<MarketologsListDTO>) personalService.getMarketologsAndCountToDateToOwner(marketologs, date);
    }

    @SuppressWarnings("unchecked")
    private List<WorkersListDTO> workersToOwner(List<Worker> workers, LocalDate date) {
        return (List<WorkersListDTO>) personalService.gerWorkersToAndCountToDateToOwner(workers, date);
    }

    @SuppressWarnings("unchecked")
    private List<OperatorsListDTO> operatorsToOwner(List<Operator> operators, LocalDate date) {
        return (List<OperatorsListDTO>) personalService.gerOperatorsAndCountToDateToOwner(operators, date);
    }

    private Comparator<UserData> scoreComparator(boolean financeVisible) {
        return Comparator
                .comparingInt((UserData user) -> switch (Objects.toString(user.getRole(), "")) {
                    case "ROLE_MANAGER" -> 1;
                    case "ROLE_WORKER" -> 2;
                    case "ROLE_OPERATOR" -> 3;
                    case "ROLE_MARKETOLOG" -> 4;
                    default -> 5;
                })
                .thenComparing(workerScoreComparator(financeVisible))
                .thenComparing(Comparator.comparingLong((UserData user) -> valueOrZero(user.getTotalSum())).reversed())
                .thenComparing(Comparator.comparingLong((UserData user) -> valueOrZero(user.getSalary())).reversed())
                .thenComparing(UserData::getFio, Comparator.nullsLast(String::compareToIgnoreCase));
    }

    private Comparator<UserData> workerScoreComparator(boolean financeVisible) {
        return (left, right) -> {
            boolean leftWorker = "ROLE_WORKER".equals(left.getRole());
            boolean rightWorker = "ROLE_WORKER".equals(right.getRole());
            if (!leftWorker || !rightWorker) {
                return 0;
            }

            long leftValue = financeVisible ? valueOrZero(left.getSalary()) : valueOrZero(left.getInPublish());
            long rightValue = financeVisible ? valueOrZero(right.getSalary()) : valueOrZero(right.getInPublish());
            return Long.compare(rightValue, leftValue);
        };
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    public record CabinetProfileResponse(
            LocalDate date,
            UserLKDTO user,
            UserStatDTO workerZp
    ) {
    }

    public record CabinetUserInfoResponse(
            LocalDate date,
            UserLKDTO currentUser,
            UserStatDTO workerZp
    ) {
    }

    public record TeamResponse(
            LocalDate date,
            String role,
            boolean canEditUsers,
            boolean canAddUsers,
            boolean canOpenUserInfo,
            List<ManagersListDTO> managers,
            List<MarketologsListDTO> marketologs,
            List<WorkersListDTO> workers,
            List<OperatorsListDTO> operators
    ) {
    }

    public record ScoreResponse(
            LocalDate date,
            UserLKDTO user,
            boolean financeVisible,
            Map<String, List<ScoreUserResponse>> groups
    ) {
    }

    public record ScoreUserResponse(
            String fio,
            String role,
            Long salary,
            Long totalSum,
            Long zpTotal,
            Long newCompanies,
            Long newOrders,
            Long correctOrders,
            Long inVigul,
            Long inPublish,
            Long imageId,
            Long userId,
            Long order1Month,
            Long review1Month,
            Long leadsNew,
            Long leadsInWork,
            Long percentInWork
    ) {
        static ScoreUserResponse from(UserData user, boolean financeVisible) {
            return new ScoreUserResponse(
                    user.getFio(),
                    user.getRole(),
                    financeVisible ? user.getSalary() : null,
                    financeVisible ? user.getTotalSum() : null,
                    financeVisible ? user.getZpTotal() : null,
                    financeVisible ? user.getNewCompanies() : null,
                    user.getNewOrders(),
                    user.getCorrectOrders(),
                    user.getInVigul(),
                    user.getInPublish(),
                    user.getImageId(),
                    user.getUserId(),
                    user.getOrder1Month(),
                    user.getReview1Month(),
                    user.getLeadsNew(),
                    user.getLeadsInWork(),
                    user.getPercentInWork()
            );
        }
    }

    public record AnalyticsResponse(
            LocalDate date,
            AnalyticsPeriodResponse period,
            UserLKDTO user,
            StatDTO stats
    ) {
    }

    public record AnalyticsPeriodResponse(
            LocalDate from,
            LocalDate to,
            boolean allTime
    ) {
    }

    private record AnalyticsPeriod(
            LocalDate from,
            LocalDate to,
            boolean allTime
    ) {
    }
}
