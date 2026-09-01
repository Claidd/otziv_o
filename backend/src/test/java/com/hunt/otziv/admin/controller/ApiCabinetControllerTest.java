package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.personal.ManagersListDTO;
import com.hunt.otziv.admin.dto.personal.UserData;
import com.hunt.otziv.admin.dto.personal.WorkersListDTO;
import com.hunt.otziv.admin.service.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateScoreService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateStatsService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService.AggregateTeam;
import com.hunt.otziv.analytics.service.AnalyticsAggregateUserStatsService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentAdminSummaryResponse;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentVisibilityService;
import com.hunt.otziv.manager_performance.dto.ManagerPerformanceScoreResponse;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.manager_daily_summary.service.ManagerActivityMetricsService;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.p_products.worker_access.service.WorkerNetworkViolationService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import com.hunt.otziv.worker_performance.service.TeamPatternAnalysisService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiCabinetControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);

    @Mock
    private PersonalService personalService;

    @Mock
    private UserService userService;

    @Mock
    private ManagerService managerService;

    @Mock
    private WorkerService workerService;

    @Mock
    private AnalyticsAggregateStatsService analyticsAggregateStatsService;

    @Mock
    private AnalyticsAggregateScoreService analyticsAggregateScoreService;

    @Mock
    private AnalyticsAggregateUserStatsService analyticsAggregateUserStatsService;

    @Mock
    private AnalyticsAggregateTeamService analyticsAggregateTeamService;

    @Mock
    private PaymentProfileService paymentProfileService;

    @Mock
    private ManualPaymentTaskService manualPaymentTaskService;

    @Mock
    private ManagerPerformanceService managerPerformanceService;

    @Mock
    private ManagerActivityMetricsService managerActivityMetricsService;

    @Mock
    private StaffDailyProgressService staffDailyProgressService;

    @Mock
    private WorkerNetworkViolationService workerNetworkViolationService;

    @Mock
    private TeamPatternAnalysisService teamPatternAnalysisService;

    @Mock
    private ContractorPaymentVisibilityService contractorPaymentVisibilityService;

    private ApiCabinetController controller;
    private Principal principal;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new ApiCabinetController(
                personalService,
                userService,
                managerService,
                workerService,
                new PerformanceMetrics(new SimpleMeterRegistry()),
                new ConcurrentMapCacheManager(),
                analyticsAggregateStatsService,
                analyticsAggregateScoreService,
                analyticsAggregateUserStatsService,
                analyticsAggregateTeamService,
                paymentProfileService,
                manualPaymentTaskService,
                managerPerformanceService,
                managerActivityMetricsService,
                staffDailyProgressService,
                workerNetworkViolationService,
                teamPatternAnalysisService,
                contractorPaymentVisibilityService
        );
        principal = () -> "alex";
        authentication = new UsernamePasswordAuthenticationToken(
                "alex",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        lenient().when(personalService.getUserLK(principal)).thenReturn(UserLKDTO.builder()
                .username("alex")
                .role("ADMIN")
                .build());
        lenient().when(managerPerformanceService.score(DATE)).thenReturn(List.of());
        lenient().when(workerNetworkViolationService.statsForPeriod(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Map.of());
        lenient().when(workerNetworkViolationService.statisticsVisibleForRole(
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);
    }

    @Test
    void ownerTeamUsesLegacyRowsWhenAggregateReadIsDisabled() {
        TeamFixture fixture = teamFixture();
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", false);
        stubOwnerTeamContext(fixture);
        when(personalService.getManagersAndCountToDateToOwner(List.of(fixture.manager()), DATE))
                .thenReturn(List.of(managerDto("Legacy Manager", 100)));
        when(personalService.getMarketologsAndCountToDateToOwner(List.of(fixture.marketolog()), DATE)).thenReturn(List.of());
        when(personalService.gerWorkersToAndCountToDateToOwner(List.of(fixture.worker()), DATE)).thenReturn(List.of());
        when(personalService.gerOperatorsAndCountToDateToOwner(List.of(fixture.operator()), DATE)).thenReturn(List.of());

        ApiCabinetController.TeamResponse response = controller.team(principal, ownerAuthentication(), DATE, null, true);

        assertEquals("OWNER", response.role());
        assertEquals(100, response.managers().getFirst().getSum1Month());
        verify(analyticsAggregateTeamService, never()).buildTeam(
                DATE,
                List.of(fixture.manager()),
                List.of(fixture.marketolog()),
                List.of(fixture.worker()),
                List.of(fixture.operator())
        );
    }

    @Test
    void ownerTeamUsesAggregateRowsWhenAggregateReadIsEnabled() {
        TeamFixture fixture = teamFixture();
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        stubOwnerTeamContext(fixture);
        when(analyticsAggregateTeamService.buildTeam(
                DATE,
                List.of(fixture.manager()),
                List.of(fixture.marketolog()),
                List.of(fixture.worker()),
                List.of(fixture.operator())
        )).thenReturn(Optional.of(new AggregateTeam(
                List.of(managerDto("Aggregate Manager", 200)),
                List.of(),
                List.of(),
                List.of()
        )));

        ApiCabinetController.TeamResponse response = controller.team(principal, ownerAuthentication(), DATE, null, true);

        assertEquals("OWNER", response.role());
        assertEquals(200, response.managers().getFirst().getSum1Month());
        verify(personalService, never()).getManagersAndCountToDateToOwner(List.of(fixture.manager()), DATE);
    }

    @Test
    void ownerTeamFallsBackToLegacyRowsWhenAggregateRowsAreMissing() {
        TeamFixture fixture = teamFixture();
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        stubOwnerTeamContext(fixture);
        when(analyticsAggregateTeamService.buildTeam(
                DATE,
                List.of(fixture.manager()),
                List.of(fixture.marketolog()),
                List.of(fixture.worker()),
                List.of(fixture.operator())
        )).thenReturn(Optional.empty());
        when(personalService.getManagersAndCountToDateToOwner(List.of(fixture.manager()), DATE))
                .thenReturn(List.of(managerDto("Legacy Manager", 175)));
        when(personalService.getMarketologsAndCountToDateToOwner(List.of(fixture.marketolog()), DATE)).thenReturn(List.of());
        when(personalService.gerWorkersToAndCountToDateToOwner(List.of(fixture.worker()), DATE)).thenReturn(List.of());
        when(personalService.gerOperatorsAndCountToDateToOwner(List.of(fixture.operator()), DATE)).thenReturn(List.of());

        ApiCabinetController.TeamResponse response = controller.team(principal, ownerAuthentication(), DATE, null, true);

        assertEquals(175, response.managers().getFirst().getSum1Month());
    }

    @Test
    void teamCachesNetworkAndPatternInsightsWithTheTeamResponse() {
        TeamFixture fixture = teamFixture();
        WorkersListDTO worker = WorkersListDTO.builder()
                .id(300L)
                .userId(30L)
                .fio("Worker One")
                .login("worker")
                .build();
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", false);
        stubOwnerTeamContext(fixture);
        when(personalService.getManagersAndCountToDateToOwner(List.of(fixture.manager()), DATE)).thenReturn(List.of());
        when(personalService.getMarketologsAndCountToDateToOwner(List.of(fixture.marketolog()), DATE)).thenReturn(List.of());
        when(personalService.gerWorkersToAndCountToDateToOwner(List.of(fixture.worker()), DATE)).thenReturn(List.of(worker));
        when(personalService.gerOperatorsAndCountToDateToOwner(List.of(fixture.operator()), DATE)).thenReturn(List.of());

        controller.team(principal, ownerAuthentication(), DATE, null, true);
        controller.team(principal, ownerAuthentication(), DATE, null, false);

        verify(workerNetworkViolationService, org.mockito.Mockito.times(2)).statsForPeriod(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(teamPatternAnalysisService, org.mockito.Mockito.times(1)).analyze(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(DATE.withDayOfMonth(1))
        );
        verify(personalService, org.mockito.Mockito.times(1))
                .gerWorkersToAndCountToDateToOwner(List.of(fixture.worker()), DATE);
    }

    @Test
    void managerTeamContainsOnlyAssignedOperationalRolesAndWorkerProgress() {
        User managerUser = user(10L, "Manager One");
        Manager manager = Manager.builder().id(100L).user(managerUser).build();
        WorkersListDTO worker = WorkersListDTO.builder()
                .id(300L)
                .userId(30L)
                .fio("Worker One")
                .login("worker")
                .build();
        DailyWorkProgressResponse progress = new DailyWorkProgressResponse(
                true,
                "WORKER",
                DATE,
                50,
                5,
                10,
                5,
                false,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                0,
                0
        );
        Authentication managerAuthentication = new UsernamePasswordAuthenticationToken(
                "alex",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );
        when(userService.findByUserName("alex")).thenReturn(Optional.of(managerUser));
        when(managerService.getManagerByUserId(10L)).thenReturn(manager);
        when(personalService.gerWorkersToManager(manager)).thenReturn(List.of(worker));
        when(personalService.gerOperatorsToManager(manager)).thenReturn(List.of());
        when(staffDailyProgressService.progressEnabled()).thenReturn(true);
        when(staffDailyProgressService.workerProgressBySubjects(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(DATE)
        )).thenReturn(Map.of(300L, progress));
        when(staffDailyProgressService.averageDailyActiveWorkSecondsByWorkerIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(DATE)
        )).thenReturn(Map.of(300L, 1_800L));
        when(staffDailyProgressService.monthlyWorkerProgressBySubjects(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(DATE.withDayOfMonth(1))
        )).thenReturn(Map.of(300L, progress));

        ApiCabinetController.TeamResponse response = controller.team(
                principal,
                managerAuthentication,
                DATE,
                null,
                true
        );

        assertEquals("MANAGER", response.role());
        assertEquals(List.of(), response.managers());
        assertEquals(List.of(), response.marketologs());
        assertSame(progress, response.workers().getFirst().getDailyProgress());
        assertSame(progress, response.workers().getFirst().getMonthlyProgress());
        assertEquals(1_800L, response.workers().getFirst().getAverageDailyActiveWorkSeconds());
        verify(personalService, never()).getMarketologsToManager(manager);
    }

    @Test
    void profileUsesLegacyWorkerStatsWhenAggregateReadIsDisabled() {
        User user = user(10L, "Worker One");
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", false);
        when(userService.findByUserName("alex")).thenReturn(Optional.of(user));
        when(personalService.getWorkerReviews(user, DATE)).thenReturn(workerStats(100));

        ApiCabinetController.CabinetProfileResponse response = controller.profile(principal, DATE, true);

        assertEquals(100, response.workerZp().getSum1Month());
        verify(analyticsAggregateUserStatsService, never()).buildUserStats(DATE, user);
    }

    @Test
    void profileUsesAggregateWorkerStatsWhenAggregateReadIsEnabled() {
        User user = user(10L, "Worker One");
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        when(userService.findByUserName("alex")).thenReturn(Optional.of(user));
        when(analyticsAggregateUserStatsService.buildUserStats(DATE, user)).thenReturn(Optional.of(workerStats(200)));

        ApiCabinetController.CabinetProfileResponse response = controller.profile(principal, DATE, true);

        assertEquals(200, response.workerZp().getSum1Month());
        verify(personalService, never()).getWorkerReviews(user, DATE);
    }

    @Test
    void profileFallsBackToLegacyWorkerStatsWhenAggregateStatsAreMissing() {
        User user = user(10L, "Worker One");
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        when(userService.findByUserName("alex")).thenReturn(Optional.of(user));
        when(analyticsAggregateUserStatsService.buildUserStats(DATE, user)).thenReturn(Optional.empty());
        when(personalService.getWorkerReviews(user, DATE)).thenReturn(workerStats(150));

        ApiCabinetController.CabinetProfileResponse response = controller.profile(principal, DATE, true);

        assertEquals(150, response.workerZp().getSum1Month());
    }

    @Test
    void profileIncludesTheSameDailyProgressForCurrentWorker() {
        User user = user(10L, "Worker One");
        Worker worker = Worker.builder().id(77L).user(user).build();
        DailyWorkProgressResponse progress = new DailyWorkProgressResponse(
                true,
                "WORKER",
                DATE,
                0,
                9,
                9,
                0,
                false,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                0,
                0
        );
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", false);
        when(userService.findByUserName("alex")).thenReturn(Optional.of(user));
        when(personalService.getWorkerReviews(user, DATE)).thenReturn(workerStats(100));
        when(staffDailyProgressService.progressEnabled()).thenReturn(true);
        when(workerService.getWorkerByUserId(10L)).thenReturn(worker);
        when(staffDailyProgressService.workerProgressByWorkers(List.of(worker), DATE))
                .thenReturn(Map.of(77L, progress));

        ApiCabinetController.CabinetProfileResponse response = controller.profile(principal, DATE, true);

        assertSame(progress, response.dailyProgress());
    }

    @Test
    void managerProfileIncludesAggregateProgressForAssignedWorkers() {
        User managerUser = user(10L, "Manager One");
        Manager manager = Manager.builder().id(100L).user(managerUser).build();
        WorkersListDTO worker = WorkersListDTO.builder()
                .id(300L)
                .userId(30L)
                .fio("Worker One")
                .login("worker")
                .build();
        DailyWorkProgressResponse workerProgress = new DailyWorkProgressResponse(
                true,
                "WORKER",
                DATE,
                28,
                75,
                103,
                27,
                false,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                0,
                0
        );
        DailyWorkProgressResponse teamProgress = new DailyWorkProgressResponse(
                true,
                "WORKER_TEAM",
                DATE,
                28,
                75,
                103,
                27,
                false,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                0,
                0
        );
        Authentication managerAuthentication = new UsernamePasswordAuthenticationToken(
                "alex",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );
        when(userService.findByUserName("alex")).thenReturn(Optional.of(managerUser));
        when(personalService.getWorkerReviews(managerUser, DATE)).thenReturn(workerStats(0));
        when(managerService.getManagerByUserId(10L)).thenReturn(manager);
        when(personalService.gerWorkersToManager(manager)).thenReturn(List.of(worker));
        when(staffDailyProgressService.progressEnabled()).thenReturn(true);
        when(staffDailyProgressService.workerProgressBySubjects(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(DATE)
        )).thenReturn(Map.of(300L, workerProgress));
        when(staffDailyProgressService.aggregateTeamProgressResponses(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(List.of(300L)),
                org.mockito.ArgumentMatchers.eq(DATE),
                org.mockito.ArgumentMatchers.eq("WORKER_TEAM")
        )).thenReturn(teamProgress);

        ApiCabinetController.CabinetProfileResponse response = controller.profile(
                managerAuthentication,
                DATE,
                true
        );

        assertSame(teamProgress, response.teamDailyProgress());
    }

    @Test
    void userInfoUsesAggregateWorkerStatsWhenAggregateReadIsEnabled() {
        User user = user(20L, "Selected Worker");
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        when(userService.findByIdToUserInfo(20L)).thenReturn(user);
        when(analyticsAggregateUserStatsService.buildUserStats(DATE, user)).thenReturn(Optional.of(workerStats(300)));

        ApiCabinetController.CabinetUserInfoResponse response = controller.userInfo(principal, 20L, DATE, true);

        assertEquals(300, response.workerZp().getSum1Month());
        assertEquals("alex", response.currentUser().getUsername());
        verify(personalService, never()).getWorkerReviews(user, DATE);
    }

    @Test
    void userInfoFallsBackToLegacyWorkerStatsWhenAggregateStatsAreMissing() {
        User user = user(20L, "Selected Worker");
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        when(userService.findByIdToUserInfo(20L)).thenReturn(user);
        when(analyticsAggregateUserStatsService.buildUserStats(DATE, user)).thenReturn(Optional.empty());
        when(personalService.getWorkerReviews(user, DATE)).thenReturn(workerStats(175));

        ApiCabinetController.CabinetUserInfoResponse response = controller.userInfo(principal, 20L, DATE, true);

        assertEquals(175, response.workerZp().getSum1Month());
    }

    @Test
    void scoreUsesLegacyRowsWhenAggregateReadIsDisabled() {
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", false);
        when(personalService.getPersonalsAndCountToScore(DATE)).thenReturn(List.of(scoreUser("Legacy Manager", 100L)));

        ApiCabinetController.ScoreResponse response = controller.score(principal, authentication, DATE, true);

        assertEquals(DATE, response.date());
        assertEquals("alex", response.user().getUsername());
        assertEquals(100L, response.groups().get("managers").getFirst().salary());
        verify(contractorPaymentVisibilityService).adminSummary(DATE);
        verify(analyticsAggregateScoreService, never()).buildScore(DATE);
    }

    @Test
    void scoreKeepsWorkMetricsCachedButReloadsFinanceForEveryRequest() {
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", false);
        when(personalService.getPersonalsAndCountToScore(DATE))
                .thenReturn(List.of(scoreUser("Cached Manager", 100L)));
        ContractorPaymentAdminSummaryResponse firstFinance = mock(ContractorPaymentAdminSummaryResponse.class);
        ContractorPaymentAdminSummaryResponse secondFinance = mock(ContractorPaymentAdminSummaryResponse.class);
        when(contractorPaymentVisibilityService.adminSummary(DATE))
                .thenReturn(List.of(firstFinance), List.of(secondFinance));

        ApiCabinetController.ScoreResponse first = controller.score(
                principal, authentication, DATE, false
        );
        ApiCabinetController.ScoreResponse second = controller.score(
                principal, authentication, DATE, false
        );

        assertSame(firstFinance, first.contractorPayments().getFirst());
        assertSame(secondFinance, second.contractorPayments().getFirst());
        verify(personalService).getPersonalsAndCountToScore(DATE);
        verify(contractorPaymentVisibilityService, times(2)).adminSummary(DATE);
    }

    @Test
    void scoreUsesAggregateRowsWhenAggregateReadIsEnabled() {
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        when(analyticsAggregateScoreService.buildScore(DATE)).thenReturn(Optional.of(List.of(scoreUser("Aggregate Manager", 200L))));

        ApiCabinetController.ScoreResponse response = controller.score(principal, authentication, DATE, true);

        assertEquals(200L, response.groups().get("managers").getFirst().salary());
        verify(personalService, never()).getPersonalsAndCountToScore(DATE);
    }

    @Test
    void scoreFallsBackToLegacyRowsWhenAggregateRowsAreMissing() {
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        when(analyticsAggregateScoreService.buildScore(DATE)).thenReturn(Optional.empty());
        when(personalService.getPersonalsAndCountToScore(DATE)).thenReturn(List.of(scoreUser("Legacy Manager", 150L)));

        ApiCabinetController.ScoreResponse response = controller.score(principal, authentication, DATE, true);

        assertEquals(150L, response.groups().get("managers").getFirst().salary());
    }

    @Test
    void scoreSortsManagersByLoadAdjustedPerformance() {
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", false);
        when(personalService.getPersonalsAndCountToScore(DATE)).thenReturn(List.of(
                scoreUser("Low KPI", 300L, 11L),
                scoreUser("High KPI", 100L, 12L)
        ));
        when(managerPerformanceService.score(DATE)).thenReturn(List.of(
                performance(1L, 11L, 60),
                performance(2L, 12L, 88)
        ));

        ApiCabinetController.ScoreResponse response = controller.score(principal, authentication, DATE, true);

        assertEquals("High KPI", response.groups().get("managers").getFirst().fio());
        assertEquals(88, response.groups().get("managers").getFirst().managerPerformance().loadAdjustedPerformanceScore());
    }

    @Test
    void scoreHidesFinancialFieldsForNonFinanceRoles() {
        authentication = new UsernamePasswordAuthenticationToken(
                "worker",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
        );
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        when(analyticsAggregateScoreService.buildScore(DATE)).thenReturn(Optional.of(List.of(scoreUser("Worker One", 200L))));

        ApiCabinetController.ScoreResponse response = controller.score(principal, authentication, DATE, true);

        ApiCabinetController.ScoreUserResponse worker = response.groups().get("managers").getFirst();
        assertNull(worker.salary());
        assertNull(worker.totalSum());
        assertNull(worker.zpTotal());
        assertNull(worker.newCompanies());
        verify(contractorPaymentVisibilityService, never()).adminSummary(DATE);
    }

    @Test
    void analysePrefersBusinessRoleWhenTechnicalRoleComesFirst() {
        User user = user(1L, "Admin One");
        StatDTO aggregateStats = new StatDTO();
        aggregateStats.setSum1MonthPay(200);
        authentication = new UsernamePasswordAuthenticationToken(
                "alex",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_DEFAULT-ROLES-OTZIV"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")
                )
        );
        ReflectionTestUtils.setField(controller, "aggregateAnalyticsReadEnabled", true);
        when(userService.findByUserName("alex")).thenReturn(Optional.of(user));
        when(analyticsAggregateStatsService.buildStats(
                DATE,
                user,
                "ROLE_ADMIN",
                AnalyticsAggregateStatsService.allTimeChartFrom(),
                DATE
        )).thenReturn(Optional.of(aggregateStats));

        ApiCabinetController.AnalyticsResponse response = controller.analyse(
                principal,
                authentication,
                DATE,
                null,
                null,
                true,
                true
        );

        assertEquals(200, response.stats().getSum1MonthPay());
        verify(personalService, never()).getStats(DATE, user, "ROLE_DEFAULT-ROLES-OTZIV");
    }

    private UserData scoreUser(String fio, Long salary) {
        return scoreUser(fio, salary, 10L);
    }

    private UserData scoreUser(String fio, Long salary, Long userId) {
        return UserData.builder()
                .fio(fio)
                .role("ROLE_MANAGER")
                .salary(salary)
                .totalSum(500L)
                .zpTotal(1000L)
                .newCompanies(2L)
                .newOrders(0L)
                .correctOrders(0L)
                .inVigul(0L)
                .inPublish(0L)
                .imageId(1L)
                .userId(userId)
                .order1Month(3L)
                .review1Month(7L)
                .leadsNew(0L)
                .leadsInWork(0L)
                .percentInWork(0L)
                .build();
    }

    private ManagerPerformanceScoreResponse performance(Long managerId, Long userId, int adjustedScore) {
        return new ManagerPerformanceScoreResponse(
                managerId,
                userId,
                Math.max(0, adjustedScore - 2),
                adjustedScore,
                "B",
                40.0,
                "NORMAL",
                10,
                8,
                2,
                3,
                3,
                1,
                10.0,
                0.2,
                1,
                2,
                100.0,
                100.0,
                0.0,
                0.0,
                10.0,
                20.0,
                0.0,
                0.0,
                1,
                1,
                0,
                80,
                90,
                100,
                85,
                90,
                100,
                100,
                10,
                8,
                2,
                80.0,
                98.0,
                2,
                85
        );
    }

    private User user(Long id, String fio) {
        return User.builder()
                .id(id)
                .fio(fio)
                .username("user-" + id)
                .build();
    }

    private User activeStaffUser(Long id, String fio, String roleName) {
        Role role = new Role();
        role.setName(roleName);
        return User.builder()
                .id(id)
                .fio(fio)
                .username("user-" + id)
                .active(true)
                .roles(Set.of(role))
                .build();
    }

    private Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "alex",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
    }

    private void stubOwnerTeamContext(TeamFixture fixture) {
        when(userService.findByUserName("alex")).thenReturn(Optional.of(fixture.owner()));
        when(userService.findManagersByUserName("alex")).thenReturn(Set.of(fixture.manager()));
        when(personalService.findAllManagersWorkers(List.of(fixture.manager()))).thenReturn(List.of(fixture.expandedManager()));
        when(personalService.findCurrentMarketologsForManagers(List.of(fixture.manager())))
                .thenReturn(List.of(fixture.marketolog()));
        when(personalService.findCurrentWorkersForManagers(List.of(fixture.manager())))
                .thenReturn(Set.of(fixture.worker()));
        when(personalService.findCurrentOperatorsForManagers(List.of(fixture.manager())))
                .thenReturn(Set.of(fixture.operator()));
    }

    private ManagersListDTO managerDto(String fio, int sum1Month) {
        return ManagersListDTO.builder()
                .id(100L)
                .userId(10L)
                .fio(fio)
                .login("manager")
                .imageId(1L)
                .sum1Month(sum1Month)
                .order1Month(3)
                .review1Month(7)
                .payment1Month(500)
                .build();
    }

    private UserStatDTO workerStats(int sum1Month) {
        UserStatDTO stats = new UserStatDTO();
        stats.setSum1Month(sum1Month);
        return stats;
    }

    private TeamFixture teamFixture() {
        User owner = user(1L, "Owner One");
        User managerUser = User.builder()
                .id(10L)
                .username("manager")
                .fio("Manager One")
                .build();
        Manager manager = Manager.builder()
                .id(100L)
                .user(managerUser)
                .build();
        Marketolog marketolog = Marketolog.builder()
                .id(200L)
                .user(activeStaffUser(20L, "Marketolog One", "ROLE_MARKETOLOG"))
                .build();
        Worker worker = Worker.builder()
                .id(300L)
                .user(activeStaffUser(30L, "Worker One", "ROLE_WORKER"))
                .build();
        Operator operator = Operator.builder()
                .id(400L)
                .user(activeStaffUser(40L, "Operator One", "ROLE_OPERATOR"))
                .build();

        User expandedManagerUser = User.builder()
                .id(10L)
                .username("manager")
                .fio("Manager One")
                .marketologs(Set.of(marketolog))
                .workers(Set.of(worker))
                .operators(Set.of(operator))
                .build();
        Manager expandedManager = Manager.builder()
                .id(100L)
                .user(expandedManagerUser)
                .build();

        return new TeamFixture(owner, manager, expandedManager, marketolog, worker, operator);
    }

    private record TeamFixture(
            User owner,
            Manager manager,
            Manager expandedManager,
            Marketolog marketolog,
            Worker worker,
            Operator operator
    ) {
    }
}
