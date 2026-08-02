package com.hunt.otziv.r_review.capability.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ReviewCheckCapabilityRepositoryTest {

    @Test
    void legacyUseUpsertThrottlesTimestampWritesWithTheDatabaseClock() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ReviewCheckCapabilityRepository repository = new ReviewCheckCapabilityRepository(jdbc);

        repository.recordLegacyUse(UUID.randomUUID(), new byte[32]);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(MapSqlParameterSource.class));
        assertThat(sql.getValue()).contains(
                "ON DUPLICATE KEY UPDATE",
                "last_used_at <= TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(6))",
                "last_used_at = IF("
        );
    }

    @Test
    void opaqueUseAcceptsAThrottledNoOpAndChecksActivityWithTheDatabaseClock() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ReviewCheckCapabilityRepository repository = new ReviewCheckCapabilityRepository(jdbc);
        when(jdbc.queryForObject(
                anyString(),
                any(MapSqlParameterSource.class),
                eq(Integer.class)
        )).thenReturn(1);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);

        assertThat(repository.isActiveByDatabaseClock(12L)).isTrue();
        assertThat(repository.touchIfActiveAndDue(12L)).isZero();

        ArgumentCaptor<String> activitySql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(
                activitySql.capture(),
                any(MapSqlParameterSource.class),
                eq(Integer.class)
        );
        assertThat(activitySql.getValue()).contains(
                "revoked_at IS NULL",
                "expires_at > CURRENT_TIMESTAMP(6)"
        );

        ArgumentCaptor<String> touchSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(touchSql.capture(), any(MapSqlParameterSource.class));
        assertThat(touchSql.getValue()).contains(
                "last_used_at <= TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(6))",
                "expires_at > CURRENT_TIMESTAMP(6)"
        );
    }
}
