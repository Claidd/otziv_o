package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.personal.ManagersListDTO;
import com.hunt.otziv.admin.dto.personal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.personal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.personal.UserData;
import com.hunt.otziv.admin.dto.personal.WorkersListDTO;
import com.hunt.otziv.admin.service.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateScoreService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateStatsService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateUserStatsService;
import com.hunt.otziv.config.cache.CacheConfig;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentAdminSummaryResponse;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentVisibilityService;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.manager_daily_summary.service.ManagerActivityMetricsService;
import com.hunt.otziv.payments.dto.CreateManualPaymentTaskRequest;
import com.hunt.otziv.payments.dto.ManagerManualPaymentSettingsResponse;
import com.hunt.otziv.payments.dto.ManualPaymentTaskAccountingTargetOption;
import com.hunt.otziv.payments.dto.ManualPaymentTaskResponse;
import com.hunt.otziv.payments.dto.UpdateManagerManualPaymentSettingsRequest;
import com.hunt.otziv.payments.dto.UpdateManualPaymentTaskRequest;
import com.hunt.otziv.payments.dto.UpdateManualPaymentTaskStatusRequest;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.p_products.worker_access.dto.WorkerNetworkViolationStatsResponse;
import com.hunt.otziv.p_products.worker_access.service.WorkerNetworkViolationService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.dto.TeamPatternAnalysisResponse;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import com.hunt.otziv.worker_performance.service.TeamPatternAnalysisService;
import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cabinet")
public class ApiCabinetController {
    private static final ZoneId CABINET_ZONE = ZoneId.of("Asia/Irkutsk");


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
    private final WorkerService workerService;
    private final PerformanceMetrics performanceMetrics;
    private final CacheManager cacheManager;
    private final AnalyticsAggregateStatsService analyticsAggregateStatsService;
    private final AnalyticsAggregateScoreService analyticsAggregateScoreService;
    private final AnalyticsAggregateUserStatsService analyticsAggregateUserStatsService;
    private final AnalyticsAggregateTeamService analyticsAggregateTeamService;
    private final PaymentProfileService paymentProfileService;
    private final ManualPaymentTaskService manualPaymentTaskService;
    private final ManagerPerformanceService managerPerformanceService;
    private final ManagerActivityMetricsService managerActivityMetricsService;
    private final StaffDailyProgressService staffDailyProgressService;
    private final WorkerNetworkViolationService workerNetworkViolationService;
    private final TeamPatternAnalysisService teamPatternAnalysisService;
    private final ContractorPaymentVisibilityService contractorPaymentVisibilityService;

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
                                workerStats(selectedDate, user),
                                managerPerformance(selectedDate, user, principal),
                                workerDailyProgress(selectedDate, user),
                                managerTeamDailyProgress(selectedDate, user, principal)
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

    @GetMapping("/payment-profile/manual")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ManagerManualPaymentSettingsResponse managerManualPaymentSettings(Principal principal) {
        User user = currentUser(principal);
        return paymentProfileService.managerManualPaymentSettings(user.getId());
    }

    @PutMapping("/payment-profile/manual")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ManagerManualPaymentSettingsResponse updateManagerManualPaymentSettings(
            Principal principal,
            @RequestBody UpdateManagerManualPaymentSettingsRequest request
    ) {
        User user = currentUser(principal);
        return paymentProfileService.updateManagerManualPaymentSettings(user.getId(), request);
    }

    @GetMapping("/manual-payment-tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ResponseEntity<List<ManualPaymentTaskResponse>> manualPaymentTasks(Principal principal) {
        User user = currentUser(principal);
        return noStore(manualPaymentTaskService.managerTasks(user.getId()));
    }

    @GetMapping("/manual-payment-tasks/accounting-targets")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ResponseEntity<List<ManualPaymentTaskAccountingTargetOption>> manualPaymentTaskAccountingTargets(
            Principal principal,
            @RequestParam(required = false) Long targetAmountKopecks,
            @RequestParam(required = false) Long taskId
    ) {
        User user = currentUser(principal);
        return noStore(manualPaymentTaskService.managerAccountingTargetOptions(
                user.getId(), targetAmountKopecks, taskId));
    }

    @PostMapping("/manual-payment-tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ResponseEntity<ManualPaymentTaskResponse> createManualPaymentTask(
            Principal principal,
            @RequestBody CreateManualPaymentTaskRequest request
    ) {
        User user = currentUser(principal);
        return noStore(manualPaymentTaskService.createManagerTask(
                user.getId(), request, principal.getName()));
    }

    @PutMapping("/manual-payment-tasks/{taskId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ResponseEntity<ManualPaymentTaskResponse> updateManualPaymentTaskStatus(
            Principal principal,
            @PathVariable Long taskId,
            @RequestBody UpdateManualPaymentTaskStatusRequest request
    ) {
        User user = currentUser(principal);
        return noStore(manualPaymentTaskService.updateManagerTaskStatus(
                user.getId(),
                taskId,
                request == null ? null : request.status(),
                principal.getName()
        ));
    }

    @PutMapping("/manual-payment-tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ResponseEntity<ManualPaymentTaskResponse> updateManualPaymentTask(
            Principal principal,
            @PathVariable Long taskId,
            @RequestBody UpdateManualPaymentTaskRequest request
    ) {
        User user = currentUser(principal);
        return noStore(manualPaymentTaskService.updateManagerTask(
                user.getId(),
                taskId,
                request,
                principal.getName()
        ));
    }

    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public TeamResponse team(
            Principal principal,
            Authentication authentication,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(value = "month", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate month,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh
    ) {
        return performanceMetrics.recordEndpoint("cabinet.team", () -> {
            LocalDate selectedDate = selectedDate(date);
            LocalDate selectedMonth = selectedMonth(month, selectedDate);
            String role = primaryRole(authentication);

            return cached(
                    CacheConfig.CABINET_TEAM,
                    cabinetKey("team", principal.getName(), role, selectedDate, selectedMonth, aggregateAnalyticsReadEnabled),
                    refresh,
                    () -> withTeamInsights(
                            teamResponse(principal, authentication, selectedDate, selectedMonth, role),
                            selectedDate,
                            selectedMonth
                    )
            );
        });
    }

    private TeamResponse teamResponse(
            Principal principal,
            Authentication authentication,
            LocalDate selectedDate,
            LocalDate selectedMonth,
            String role
    ) {
        User user = currentUser(principal);
        boolean canManageUsers = hasAnyRole(authentication, "ROLE_ADMIN", "ROLE_OWNER");

        if ("ROLE_MANAGER".equals(role)) {
            Manager manager = managerService.getManagerByUserId(user.getId());
            return withTeamMonthlyProgress(withTeamDailyProgress(new TeamResponse(
                    selectedDate,
                    shortRole(role),
                    canManageUsers,
                    false,
                    false,
                    List.of(),
                    List.of(),
                    personalService.gerWorkersToManager(manager),
                    personalService.gerOperatorsToManager(manager),
                    null
            ), selectedDate, true), selectedMonth, true);
        }

        if ("ROLE_OWNER".equals(role)) {
            List<Manager> managers = userService.findManagersByUserName(principal.getName()).stream().toList();
            personalService.findAllManagersWorkers(managers);
            List<Marketolog> marketologs = personalService.findCurrentMarketologsForManagers(managers);
            List<Operator> operators = personalService.findCurrentOperatorsForManagers(managers).stream().toList();
            List<Worker> workers = personalService.findCurrentWorkersForManagers(managers).stream().toList();

            return withTeamMonthlyProgress(withTeamDailyProgress(ownerTeamResponse(
                    selectedDate,
                    role,
                    managers,
                    marketologs,
                    workers,
                    operators
            ), selectedDate, true), selectedMonth, true);
        }

        return withTeamMonthlyProgress(withTeamDailyProgress(new TeamResponse(
                selectedDate,
                shortRole(role),
                canManageUsers,
                canManageUsers,
                true,
                personalService.getManagers(),
                personalService.getMarketologs(),
                personalService.gerWorkers(),
                personalService.gerOperators(),
                null
        ), selectedDate, canManageUsers), selectedMonth, canManageUsers);
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
            boolean managerPerformanceVisible = financeVisible;

            return cached(
                    CacheConfig.CABINET_SCORE,
                    cabinetKey("score", principal.getName(), financeVisible, managerPerformanceVisible, selectedDate, aggregateAnalyticsReadEnabled),
                    refresh,
                    () -> {
                        Map<Long, ManagerPerformanceScoreResponse> managerPerformanceByUserId = managerPerformanceVisible
                                ? managerPerformanceService.score(selectedDate).stream()
                                .filter(item -> item.managerUserId() != null)
                                .collect(Collectors.toMap(
                                        ManagerPerformanceScoreResponse::managerUserId,
                                        Function.identity(),
                                        (left, right) -> left
                                ))
                                : Map.of();
                        Map<String, List<ScoreUserResponse>> groupedUsers = scoreRows(selectedDate).stream()
                                .sorted(scoreComparator(financeVisible, managerPerformanceByUserId))
                                .map(user -> ScoreUserResponse.from(user, financeVisible, managerPerformanceByUserId.get(user.getUserId())))
                                .collect(Collectors.groupingBy(
                                        ScoreUserResponse::role,
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                ));

                        return new ScoreResponse(
                                selectedDate,
                                personalService.getUserLK(principal),
                                financeVisible,
                                managerPerformanceVisible,
                                financeVisible ? contractorPaymentVisibilityService.adminSummary() : List.of(),
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
        return date == null ? LocalDate.now(CABINET_ZONE) : date;
    }

    private LocalDate selectedMonth(LocalDate month, LocalDate selectedDate) {
        return (month == null ? selectedDate : month).withDayOfMonth(1);
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
                            team.operators(),
                            null
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
                operatorsToOwner(operators, selectedDate),
                null
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

    private TeamResponse withTeamDailyProgress(TeamResponse response, LocalDate selectedDate, boolean visible) {
        if (!visible || response == null || !staffDailyProgressService.progressEnabled()) {
            return response;
        }

        ManagerWorkerProgressContext managerWorkerContext = managerWorkerProgressContext(response.managers());
        Map<Long, StaffDailyProgressService.WorkerProgressSubject> workerSubjectsById = new LinkedHashMap<>(managerWorkerContext.workerSubjectsById());

        response.workers().stream()
                .filter(worker -> worker.getId() != null)
                .forEach(worker -> workerSubjectsById.putIfAbsent(
                        worker.getId(),
                        new StaffDailyProgressService.WorkerProgressSubject(
                                worker.getId(),
                                worker.getUserId(),
                                firstNonBlank(worker.getFio(), worker.getLogin())
                        )
                ));
        Map<Long, DailyWorkProgressResponse> workerProgress = staffDailyProgressService.workerProgressBySubjects(
                workerSubjectsById.values(),
                selectedDate
        );
        response.workers().forEach(worker ->
                worker.setDailyProgress(workerProgress.get(worker.getId()))
        );
        Map<Long, Long> workerAverageDailyActivity =
                staffDailyProgressService.averageDailyActiveWorkSecondsByWorkerIds(
                        workerSubjectsById.keySet(),
                        selectedDate
                );
        response.workers().forEach(worker -> worker.setAverageDailyActiveWorkSeconds(
                workerAverageDailyActivity.getOrDefault(worker.getId(), 0L)
        ));
        response.managers().forEach(manager -> {
            List<DailyWorkProgressResponse> teamProgress = managerWorkerContext.workerIdsByManagerId()
                    .getOrDefault(manager.getId(), List.of()).stream()
                    .map(workerProgress::get)
                    .filter(Objects::nonNull)
                    .toList();
            List<Long> teamWorkerIds = managerWorkerContext.workerIdsByManagerId()
                    .getOrDefault(manager.getId(), List.of());
            DailyWorkProgressResponse teamProgressResponse = staffDailyProgressService.aggregateTeamProgressResponses(
                    teamProgress,
                    teamWorkerIds,
                    selectedDate,
                    "WORKER_TEAM"
            );
            ManagerActivityMetricsService.DailyAndAverage managerActivity =
                    managerActivityMetricsService.calculateDailyAndMonthAverage(
                            manager.getId(),
                            selectedDate,
                            selectedDate.equals(LocalDate.now(CABINET_ZONE))
                                    ? java.time.LocalDateTime.now(CABINET_ZONE)
                                    : selectedDate.plusDays(1).atStartOfDay()
                    );
            manager.setDailyProgress(teamProgressResponse.withActiveWorkSeconds(
                    managerActivity.daily().confirmedSeconds()
            ));
            manager.setAverageDailyActiveWorkSeconds(managerActivity.averageDailyConfirmedSeconds());
        });
        return response;
    }

    private TeamResponse withTeamMonthlyProgress(TeamResponse response, LocalDate selectedMonth, boolean visible) {
        if (!visible || response == null || !staffDailyProgressService.progressEnabled()) {
            return response;
        }

        LocalDate monthStart = selectedMonth(selectedMonth, response.date());
        ManagerWorkerProgressContext managerWorkerContext = managerWorkerProgressContext(response.managers());
        Map<Long, StaffDailyProgressService.WorkerProgressSubject> workerSubjectsById = new LinkedHashMap<>(managerWorkerContext.workerSubjectsById());

        response.workers().stream()
                .filter(worker -> worker.getId() != null)
                .forEach(worker -> workerSubjectsById.putIfAbsent(
                        worker.getId(),
                        new StaffDailyProgressService.WorkerProgressSubject(
                                worker.getId(),
                                worker.getUserId(),
                                firstNonBlank(worker.getFio(), worker.getLogin())
                        )
                ));
        Map<Long, DailyWorkProgressResponse> workerProgress = staffDailyProgressService.monthlyWorkerProgressBySubjects(
                workerSubjectsById.values(),
                monthStart
        );
        response.workers().forEach(worker ->
                worker.setMonthlyProgress(workerProgress.get(worker.getId()))
        );
        response.managers().forEach(manager -> {
            List<DailyWorkProgressResponse> teamProgress = managerWorkerContext.workerIdsByManagerId()
                    .getOrDefault(manager.getId(), List.of()).stream()
                    .map(workerProgress::get)
                    .filter(Objects::nonNull)
                    .toList();
            DailyWorkProgressResponse teamProgressResponse = staffDailyProgressService.aggregateProgressResponses(
                    teamProgress,
                    monthStart,
                    "WORKER_TEAM_MONTH"
            );
            LocalDate currentMonth = LocalDate.now(CABINET_ZONE).withDayOfMonth(1);
            java.time.LocalDateTime activityUntil = monthStart.equals(currentMonth)
                    ? java.time.LocalDateTime.now(CABINET_ZONE)
                    : monthStart.plusMonths(1).atStartOfDay();
            ManagerActivityMetricsService.Metrics managerActivity = managerActivityMetricsService.calculate(
                    manager.getId(),
                    monthStart.atStartOfDay(),
                    activityUntil
            );
            manager.setMonthlyProgress(teamProgressResponse.withActiveWorkSeconds(
                    managerActivity.confirmedSeconds()
            ));
        });
        return response;
    }

    private TeamResponse withTeamInsights(
            TeamResponse response,
            LocalDate selectedDate,
            LocalDate selectedMonth
    ) {
        return withTeamPatterns(
                withTeamNetworkViolations(response, selectedDate, selectedMonth),
                selectedMonth
        );
    }

    private TeamResponse withTeamNetworkViolations(
            TeamResponse response,
            LocalDate selectedDate,
            LocalDate selectedMonth
    ) {
        if (response == null
                || !workerNetworkViolationService.statisticsVisibleForRole(response.role())
                || response.workers() == null
                || response.workers().isEmpty()) {
            return response;
        }

        List<Long> userIds = response.workers().stream()
                .map(WorkersListDTO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, WorkerNetworkViolationStatsResponse> daily = workerNetworkViolationService.statsForPeriod(
                userIds,
                selectedDate,
                selectedDate.plusDays(1)
        );
        LocalDate monthStart = selectedMonth.withDayOfMonth(1);
        Map<Long, WorkerNetworkViolationStatsResponse> monthly = workerNetworkViolationService.statsForPeriod(
                userIds,
                monthStart,
                monthStart.plusMonths(1)
        );
        response.workers().forEach(worker -> {
            worker.setDailyNetworkViolations(daily.getOrDefault(
                    worker.getUserId(),
                    WorkerNetworkViolationStatsResponse.empty()
            ));
            worker.setMonthlyNetworkViolations(monthly.getOrDefault(
                    worker.getUserId(),
                    WorkerNetworkViolationStatsResponse.empty()
            ));
        });
        return response;
    }

    private TeamResponse withTeamPatterns(TeamResponse response, LocalDate selectedMonth) {
        if (response == null
                || !workerNetworkViolationService.statisticsVisibleForRole(response.role())
                || response.workers() == null
                || response.workers().isEmpty()) {
            return response;
        }
        TeamPatternAnalysisResponse patterns = teamPatternAnalysisService.analyze(
                response.workers().stream()
                        .filter(worker -> worker.getId() != null && worker.getUserId() != null)
                        .map(worker -> new TeamPatternAnalysisService.WorkerPatternSubject(
                                worker.getId(),
                                worker.getUserId(),
                                firstNonBlank(worker.getFio(), worker.getLogin())
                        ))
                        .toList(),
                selectedMonth
        );
        return new TeamResponse(
                response.date(),
                response.role(),
                response.canEditUsers(),
                response.canAddUsers(),
                response.canOpenUserInfo(),
                response.managers(),
                response.marketologs(),
                response.workers(),
                response.operators(),
                patterns
        );
    }

    private ManagerWorkerProgressContext managerWorkerProgressContext(List<ManagersListDTO> managerDtos) {
        if (managerDtos == null || managerDtos.isEmpty()) {
            return ManagerWorkerProgressContext.empty();
        }

        List<Long> managerIds = managerDtos.stream()
                .map(ManagersListDTO::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (managerIds.isEmpty()) {
            return ManagerWorkerProgressContext.empty();
        }

        List<Manager> selectedManagers = managerService.getAllManagers().stream()
                .filter(manager -> manager.getId() != null && managerIds.contains(manager.getId()))
                .toList();
        List<Manager> managersWithWorkers = selectedManagers.isEmpty()
                ? List.of()
                : personalService.findAllManagersWorkers(selectedManagers);

        Map<Long, List<Long>> workerIdsByManagerId = new LinkedHashMap<>();
        Map<Long, StaffDailyProgressService.WorkerProgressSubject> workerSubjectsById = new LinkedHashMap<>();
        managersWithWorkers.forEach(manager -> {
            if (manager.getId() == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
                return;
            }
            List<Long> workerIds = manager.getUser().getWorkers().stream()
                    .filter(Objects::nonNull)
                    .filter(worker -> worker.getId() != null)
                    .peek(worker -> workerSubjectsById.putIfAbsent(
                            worker.getId(),
                            new StaffDailyProgressService.WorkerProgressSubject(
                                    worker.getId(),
                                    worker.getUser() == null ? null : worker.getUser().getId(),
                                    workerName(worker)
                            )
                    ))
                    .map(Worker::getId)
                    .distinct()
                    .toList();
            workerIdsByManagerId.put(manager.getId(), workerIds);
        });

        return new ManagerWorkerProgressContext(workerIdsByManagerId, workerSubjectsById);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    private String workerName(Worker worker) {
        return worker == null || worker.getUser() == null
                ? null
                : firstNonBlank(worker.getUser().getFio(), worker.getUser().getUsername());
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

    private Comparator<UserData> scoreComparator(
            boolean financeVisible,
            Map<Long, ManagerPerformanceScoreResponse> managerPerformanceByUserId
    ) {
        return Comparator
                .comparingInt((UserData user) -> switch (Objects.toString(user.getRole(), "")) {
                    case "ROLE_MANAGER" -> 1;
                    case "ROLE_WORKER" -> 2;
                    case "ROLE_OPERATOR" -> 3;
                    case "ROLE_MARKETOLOG" -> 4;
                    default -> 5;
                })
                .thenComparing(managerPerformanceComparator(managerPerformanceByUserId))
                .thenComparing(workerScoreComparator(financeVisible))
                .thenComparing(Comparator.comparingLong((UserData user) -> valueOrZero(user.getTotalSum())).reversed())
                .thenComparing(Comparator.comparingLong((UserData user) -> valueOrZero(user.getSalary())).reversed())
                .thenComparing(UserData::getFio, Comparator.nullsLast(String::compareToIgnoreCase));
    }

    private Comparator<UserData> managerPerformanceComparator(
            Map<Long, ManagerPerformanceScoreResponse> managerPerformanceByUserId
    ) {
        return (left, right) -> {
            boolean leftManager = "ROLE_MANAGER".equals(left.getRole());
            boolean rightManager = "ROLE_MANAGER".equals(right.getRole());
            if (!leftManager || !rightManager) {
                return 0;
            }
            ManagerPerformanceScoreResponse leftPerformance = managerPerformanceByUserId.get(left.getUserId());
            ManagerPerformanceScoreResponse rightPerformance = managerPerformanceByUserId.get(right.getUserId());
            int leftScore = leftPerformance == null ? 0 : leftPerformance.loadAdjustedPerformanceScore();
            int rightScore = rightPerformance == null ? 0 : rightPerformance.loadAdjustedPerformanceScore();
            return Integer.compare(rightScore, leftScore);
        };
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

    private ManagerPerformanceScoreResponse managerPerformance(LocalDate selectedDate, User user, Principal principal) {
        if (user == null
                || user.getId() == null
                || !(principal instanceof Authentication authentication)
                || !hasAnyRole(authentication, "ROLE_MANAGER")) {
            return null;
        }

        return managerPerformanceService.score(selectedDate).stream()
                .filter(score -> Objects.equals(score.managerUserId(), user.getId()))
                .findFirst()
                .orElse(null);
    }

    private DailyWorkProgressResponse workerDailyProgress(LocalDate selectedDate, User user) {
        if (user == null || user.getId() == null || !staffDailyProgressService.progressEnabled()) {
            return null;
        }

        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null || worker.getId() == null) {
            return null;
        }

        return staffDailyProgressService.workerProgressByWorkers(List.of(worker), selectedDate)
                .get(worker.getId());
    }

    private DailyWorkProgressResponse managerTeamDailyProgress(
            LocalDate selectedDate,
            User user,
            Principal principal
    ) {
        if (user == null
                || user.getId() == null
                || !(principal instanceof Authentication authentication)
                || !hasAnyRole(authentication, "ROLE_MANAGER")
                || !staffDailyProgressService.progressEnabled()) {
            return null;
        }

        Manager manager = managerService.getManagerByUserId(user.getId());
        List<WorkersListDTO> workers = personalService.gerWorkersToManager(manager);
        List<StaffDailyProgressService.WorkerProgressSubject> subjects = workers.stream()
                .filter(worker -> worker.getId() != null)
                .map(worker -> new StaffDailyProgressService.WorkerProgressSubject(
                        worker.getId(),
                        worker.getUserId(),
                        firstNonBlank(worker.getFio(), worker.getLogin())
                ))
                .toList();
        Map<Long, DailyWorkProgressResponse> progress = staffDailyProgressService.workerProgressBySubjects(
                subjects,
                selectedDate
        );
        List<Long> workerIds = subjects.stream()
                .map(StaffDailyProgressService.WorkerProgressSubject::workerId)
                .toList();
        return staffDailyProgressService.aggregateTeamProgressResponses(
                progress.values(),
                workerIds,
                selectedDate,
                "WORKER_TEAM"
        );
    }

    public record CabinetProfileResponse(
            LocalDate date,
            UserLKDTO user,
            UserStatDTO workerZp,
            ManagerPerformanceScoreResponse managerPerformance,
            DailyWorkProgressResponse dailyProgress,
            DailyWorkProgressResponse teamDailyProgress
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
            List<OperatorsListDTO> operators,
            TeamPatternAnalysisResponse patterns
    ) {
    }

    private record ManagerWorkerProgressContext(
            Map<Long, List<Long>> workerIdsByManagerId,
            Map<Long, StaffDailyProgressService.WorkerProgressSubject> workerSubjectsById
    ) {
        static ManagerWorkerProgressContext empty() {
            return new ManagerWorkerProgressContext(Map.of(), Map.of());
        }
    }

    public record ScoreResponse(
            LocalDate date,
            UserLKDTO user,
            boolean financeVisible,
            boolean managerPerformanceVisible,
            List<ContractorPaymentAdminSummaryResponse> contractorPayments,
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
            Long percentInWork,
            ManagerPerformanceScoreResponse managerPerformance
    ) {
        static ScoreUserResponse from(
                UserData user,
                boolean financeVisible,
                ManagerPerformanceScoreResponse managerPerformance
        ) {
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
                    user.getPercentInWork(),
                    "ROLE_MANAGER".equals(user.getRole()) ? managerPerformance : null
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
