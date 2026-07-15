package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerNetworkViolationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private WorkerCellularAccessProperties properties;
    private WorkerNetworkViolationService service;

    @BeforeEach
    void setUp() {
        properties = new WorkerCellularAccessProperties();
        properties.setViolationStatisticsEnabled(true);
        service = new WorkerNetworkViolationService(properties, userRepository, jdbcTemplate);
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
                "192.0.2.0/24"
        );

        verify(jdbcTemplate).update(anyString(), any(MapSqlParameterSource.class));
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
                "192.0.2.0/24"
        );

        verify(userRepository, never()).findByUsername(anyString());
        verify(jdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsVisibleEmptyStatsForWorkersWithoutViolations() {
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenReturn(List.of());

        Map<Long, ?> stats = service.statsForPeriod(
                List.of(42L),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 16)
        );

        assertEquals(1, stats.size());
    }
}
