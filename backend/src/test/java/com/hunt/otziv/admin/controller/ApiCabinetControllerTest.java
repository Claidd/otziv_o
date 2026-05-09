package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.UserData;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateScoreService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateStatsService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService.AggregateTeam;
import com.hunt.otziv.analytics.service.AnalyticsAggregateUserStatsService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    private AnalyticsAggregateStatsService analyticsAggregateStatsService;

    @Mock
    private AnalyticsAggregateScoreService analyticsAggregateScoreService;

    @Mock
    private AnalyticsAggregateUserStatsService analyticsAggregateUserStatsService;

    @Mock
    private AnalyticsAggregateTeamService analyticsAggregateTeamService;

    private ApiCabinetController controller;
    private Principal principal;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new ApiCabinetController(
                personalService,
                userService,
                managerService,
                new PerformanceMetrics(new SimpleMeterRegistry()),
                new ConcurrentMapCacheManager(),
                analyticsAggregateStatsService,
                analyticsAggregateScoreService,
                analyticsAggregateUserStatsService,
                analyticsAggregateTeamService
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

        ApiCabinetController.TeamResponse response = controller.team(principal, ownerAuthentication(), DATE, true);

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

        ApiCabinetController.TeamResponse response = controller.team(principal, ownerAuthentication(), DATE, true);

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

        ApiCabinetController.TeamResponse response = controller.team(principal, ownerAuthentication(), DATE, true);

        assertEquals(175, response.managers().getFirst().getSum1Month());
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
        verify(analyticsAggregateScoreService, never()).buildScore(DATE);
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
    }

    private UserData scoreUser(String fio, Long salary) {
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
                .userId(10L)
                .order1Month(3L)
                .review1Month(7L)
                .leadsNew(0L)
                .leadsInWork(0L)
                .percentInWork(0L)
                .build();
    }

    private User user(Long id, String fio) {
        return User.builder()
                .id(id)
                .fio(fio)
                .username("user-" + id)
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
                .user(user(20L, "Marketolog One"))
                .build();
        Worker worker = Worker.builder()
                .id(300L)
                .user(user(30L, "Worker One"))
                .build();
        Operator operator = Operator.builder()
                .id(400L)
                .user(user(40L, "Operator One"))
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
