package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamComparisonService.AnalyticsTeamComparison;
import com.hunt.otziv.analytics.service.AnalyticsAggregateTeamService.AggregateTeam;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsAggregateTeamComparisonServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 9);

    @Mock
    private PersonalService personalService;

    @Mock
    private UserService userService;

    @Mock
    private ManagerService managerService;

    @Mock
    private MarketologService marketologService;

    @Mock
    private WorkerService workerService;

    @Mock
    private OperatorService operatorService;

    @Mock
    private AnalyticsAggregateTeamService aggregateTeamService;

    private AnalyticsAggregateTeamComparisonService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new AnalyticsAggregateTeamComparisonService(
                personalService,
                userService,
                managerService,
                marketologService,
                workerService,
                operatorService,
                aggregateTeamService
        );

        Role role = new Role();
        role.setName("ROLE_ADMIN");
        admin = User.builder()
                .id(1L)
                .username("admin")
                .roles(List.of(role))
                .build();
    }

    @Test
    void comparesAdminTeamCoveredFields() {
        Manager manager = Manager.builder()
                .id(10L)
                .user(User.builder().id(100L).fio("Manager One").username("manager").build())
                .build();
        List<Manager> managers = List.of(manager);
        List<MarketologsListDTO> emptyMarketologs = List.of();
        List<WorkersListDTO> emptyWorkers = List.of();
        List<OperatorsListDTO> emptyOperators = List.of();

        ManagersListDTO legacyManager = ManagersListDTO.builder()
                .id(10L)
                .userId(100L)
                .fio("Manager One")
                .sum1Month(100)
                .order1Month(3)
                .review1Month(7)
                .payment1Month(500)
                .build();
        ManagersListDTO aggregateManager = ManagersListDTO.builder()
                .id(10L)
                .userId(100L)
                .fio("Manager One")
                .sum1Month(125)
                .order1Month(3)
                .review1Month(7)
                .payment1Month(500)
                .build();
        WorkersListDTO aggregateWorker = WorkersListDTO.builder()
                .id(20L)
                .userId(200L)
                .fio("Worker One")
                .sum1Month(10)
                .order1Month(1)
                .review1Month(1)
                .build();

        when(userService.findByUserName("admin")).thenReturn(Optional.of(admin));
        when(managerService.getAllManagers()).thenReturn(managers);
        when(marketologService.getAllMarketologs()).thenReturn(List.of());
        when(workerService.getAllWorkers()).thenReturn(List.of());
        when(operatorService.getAllOperators()).thenReturn(List.of());
        when(personalService.getManagersAndCountToDate(DATE)).thenReturn(List.of(legacyManager));
        when(personalService.getMarketologsAndCountToDate(DATE)).thenReturn(emptyMarketologs);
        when(personalService.gerWorkersToAndCountToDate(DATE)).thenReturn(emptyWorkers);
        when(personalService.gerOperatorsAndCountToDate(DATE)).thenReturn(emptyOperators);
        when(aggregateTeamService.buildTeam(DATE, managers, List.of(), List.of(), List.of()))
                .thenReturn(Optional.of(new AggregateTeam(
                        List.of(aggregateManager),
                        emptyMarketologs,
                        List.of(aggregateWorker),
                        emptyOperators
                )));

        AnalyticsTeamComparison comparison = service.compare("admin", DATE, null);

        assertTrue(comparison.aggregateAvailable());
        assertFalse(comparison.matches());
        assertEquals(1, comparison.legacyRowCount());
        assertEquals(2, comparison.aggregateRowCount());
        assertEquals(1, comparison.comparedRowCount());
        assertEquals(2, comparison.mismatchCount());
        assertTrue(comparison.mismatches().stream()
                .anyMatch(field -> field.rowKey().contains("Manager One")
                        && "sum1Month".equals(field.field())
                        && "25".equals(field.delta())));
        assertTrue(comparison.mismatches().stream()
                .anyMatch(field -> field.rowKey().contains("Worker One")
                        && "row".equals(field.field())
                        && "missing".equals(field.legacyValue())));
        assertEquals(List.of("newOrder", "inCorrect", "intVigul", "publish"),
                comparison.skippedFields().get("workers").stream()
                        .filter(field -> List.of("newOrder", "inCorrect", "intVigul", "publish").contains(field))
                        .toList());
    }

    @Test
    void reportsMissingAggregatesForSupportedRole() {
        when(userService.findByUserName("admin")).thenReturn(Optional.of(admin));
        when(managerService.getAllManagers()).thenReturn(List.of());
        when(marketologService.getAllMarketologs()).thenReturn(List.of());
        when(workerService.getAllWorkers()).thenReturn(List.of());
        when(operatorService.getAllOperators()).thenReturn(List.of());
        when(personalService.getManagersAndCountToDate(DATE)).thenReturn(List.of());
        when(personalService.getMarketologsAndCountToDate(DATE)).thenReturn(List.of());
        when(personalService.gerWorkersToAndCountToDate(DATE)).thenReturn(List.of());
        when(personalService.gerOperatorsAndCountToDate(DATE)).thenReturn(List.of());
        when(aggregateTeamService.buildTeam(DATE, List.of(), List.of(), List.of(), List.of()))
                .thenReturn(Optional.empty());

        AnalyticsTeamComparison comparison = service.compare("admin", DATE, "ADMIN");

        assertFalse(comparison.aggregateAvailable());
        assertFalse(comparison.matches());
        assertEquals("ROLE_ADMIN", comparison.role());
        assertEquals(0, comparison.mismatchCount());
        assertTrue(comparison.mismatches().isEmpty());
        assertTrue(comparison.comparedFields().containsKey("managers"));
    }
}
