package com.hunt.otziv.r_review.capability.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ReviewCheckCapabilityResourceRepositoryTest {

    @Test
    void archivedBindingUsesSnapshotAssignmentsAndPessimisticWriteLock() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ReviewCheckCapabilityResourceRepository repository =
                new ReviewCheckCapabilityResourceRepository(jdbc);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenReturn(List.of());

        assertThat(repository.findArchivedByOrderDetailIdForUpdate(UUID.randomUUID())).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        );
        assertThat(sql.getValue()).contains(
                "ao.order_manager AS manager_id",
                "ao.order_worker AS worker_id",
                "ao.restored_at IS NULL",
                "FOR UPDATE"
        );
    }
}
