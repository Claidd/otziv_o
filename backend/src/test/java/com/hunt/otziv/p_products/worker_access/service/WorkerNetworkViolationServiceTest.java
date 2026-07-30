package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import com.hunt.otziv.p_products.worker_access.dto.WorkerNetworkViolationStatsResponse;
import com.hunt.otziv.p_products.worker_access.repository.WorkerNetworkViolationRepository;
import com.hunt.otziv.p_products.worker_access.repository.WorkerNetworkViolationRepository.ViolationRowProjection;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerNetworkViolationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkerNetworkViolationRepository violationRepository;

    private WorkerCellularAccessProperties properties;
    private WorkerNetworkViolationService service;

    @BeforeEach
    void setUp() {
        properties = new WorkerCellularAccessProperties();
        properties.setViolationStatisticsEnabled(true);
        service = new WorkerNetworkViolationService(
                properties,
                userRepository,
                violationRepository
        );
    }

    @Test
    void recordsViolationAsEpisodeUpsert() {
        when(userRepository.findByUsername("worker")).thenReturn(Optional.of(User.builder()
                .id(42L)
                .username("worker")
                .build()));

        service.recordViolation(
                "worker",
                "publish",
                WorkerCellularAccessProperties.Mode.ENFORCE,
                "NON_CELLULAR_NETWORK",
                "Home ISP",
                "192.0.2.0/24",
                "client=capacitor;network=wifi",
                true
        );

        verify(violationRepository).upsertEpisode(
                eq(42L),
                eq("worker"),
                eq("NON_CELLULAR_NETWORK"),
                eq("publish"),
                eq("ENFORCE"),
                eq("BLOCKED"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("Home ISP"),
                eq("192.0.2.0/24"),
                eq("client=capacitor;network=wifi")
        );
    }

    @Test
    void canIgnoreUnknownNetworkByConfiguration() {
        properties.setCountUnknownNetworkViolations(false);

        service.recordViolation(
                "worker",
                "bad",
                WorkerCellularAccessProperties.Mode.AUDIT,
                "UNKNOWN_NETWORK",
                null,
                "192.0.2.0/24",
                "client=web-or-legacy",
                false
        );

        verify(userRepository, never()).findByUsername(anyString());
        verify(violationRepository, never()).upsertEpisode(
                anyLong(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(),
                any(),
                any()
        );
    }

    @Test
    void returnsVisibleEmptyStatsForWorkersWithoutViolations() {
        when(violationRepository.findActiveForUsers(
                any(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of());

        Map<Long, ?> stats = service.statsForPeriod(
                List.of(42L),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 16)
        );

        assertEquals(1, stats.size());
    }

    @Test
    void mapsRepositoryRowsIntoAggregatedStats() {
        ViolationRowProjection older = row(
                LocalDateTime.of(2026, 7, 14, 10, 0),
                LocalDateTime.of(2026, 7, 14, 10, 30),
                "NON_CELLULAR_NETWORK",
                2,
                "AUDIT_ALLOWED"
        );
        ViolationRowProjection newer = row(
                LocalDateTime.of(2026, 7, 15, 11, 0),
                LocalDateTime.of(2026, 7, 15, 11, 30),
                "VPN_PROXY_OR_DATACENTER",
                3,
                "BLOCKED"
        );
        when(violationRepository.findActiveForUsers(
                any(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of(older, newer));

        WorkerNetworkViolationStatsResponse stats = service.statsForPeriod(
                List.of(42L),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1)
        ).get(42L);

        assertEquals(2, stats.episodeCount());
        assertEquals(5, stats.attemptCount());
        assertEquals(2, stats.daysWithViolations());
        assertEquals("CRITICAL", stats.severity());
        assertEquals("VPN_PROXY_OR_DATACENTER", stats.details().get(0).reason());
        assertEquals(true, stats.details().get(0).blocked());
    }

    @Test
    void repositoryWriteFailureDoesNotBreakWorkerRequest() {
        when(userRepository.findByUsername("worker")).thenReturn(Optional.of(
                User.builder().id(42L).username("worker").build()
        ));
        doThrow(new IllegalStateException("database unavailable"))
                .when(violationRepository)
                .upsertEpisode(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        any(),
                        any(),
                        any()
                );

        assertDoesNotThrow(() -> service.recordViolation(
                "worker",
                "publish",
                WorkerCellularAccessProperties.Mode.AUDIT,
                "NON_CELLULAR_NETWORK",
                null,
                null,
                null,
                false
        ));
    }

    @Test
    void repositoryReadFailureReturnsEmptyStats() {
        when(violationRepository.findActiveForUsers(
                any(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenThrow(new IllegalStateException("database unavailable"));

        Map<Long, WorkerNetworkViolationStatsResponse> stats =
                service.statsForPeriod(
                        List.of(42L),
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 1)
                );

        assertEquals(Map.of(), stats);
    }

    private ViolationRowProjection row(
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt,
            String reason,
            long attemptCount,
            String accessResult
    ) {
        ViolationRowProjection row = mock(ViolationRowProjection.class);
        when(row.getUserId()).thenReturn(42L);
        when(row.getFirstSeenAt()).thenReturn(firstSeenAt);
        when(row.getLastSeenAt()).thenReturn(lastSeenAt);
        when(row.getReason()).thenReturn(reason);
        when(row.getScope()).thenReturn("publish");
        when(row.getAttemptCount()).thenReturn(attemptCount);
        when(row.getProvider()).thenReturn("provider");
        when(row.getClientEvidence()).thenReturn("evidence");
        when(row.getAccessResult()).thenReturn(accessResult);
        return row;
    }
}
