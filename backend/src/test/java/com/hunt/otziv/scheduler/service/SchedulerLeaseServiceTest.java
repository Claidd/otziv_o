package com.hunt.otziv.scheduler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SchedulerLeaseServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void acquisitionUsesAtomicExpiryTakeoverAndDatabaseClock() {
        SchedulerLeaseService service = new SchedulerLeaseService(jdbc);
        ReflectionTestUtils.setField(service, "instanceId", "node-a");
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<SchedulerLeaseService.Lease>>any()
        )).thenReturn(List.of(new SchedulerLeaseService.Lease("client-messages", "token", 3)));

        Optional<SchedulerLeaseService.Lease> acquired = service.tryAcquire(
                "client-messages",
                Duration.ofMinutes(10)
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(MapSqlParameterSource.class));
        assertThat(acquired).isPresent();
        assertThat(sql.getValue())
                .contains("INSERT INTO scheduler_leases")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("lease_until <= CURRENT_TIMESTAMP(6)")
                .contains("fencing_token + 1");
    }

    @Test
    void rejectsLeaseNamesOutsideTheStorageContract() {
        SchedulerLeaseService service = new SchedulerLeaseService(jdbc);

        assertThatThrownBy(() -> service.tryAcquire("bad lease name", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
