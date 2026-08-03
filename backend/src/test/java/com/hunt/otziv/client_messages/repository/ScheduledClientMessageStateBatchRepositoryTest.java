package com.hunt.otziv.client_messages.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateBatchRepository.StateSeed;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ScheduledClientMessageStateBatchRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void writesCandidatesInOneIdempotentStatementWithoutPreselects() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(2);
        LocalDateTime dueAt = LocalDateTime.of(2026, 8, 3, 12, 0);

        int affected = new ScheduledClientMessageStateBatchRepository(jdbc).upsertAll(List.of(
                seed(ClientMessageScenario.REVIEW_CHECK_REMINDER, "order:1", 1L, dueAt),
                seed(ClientMessageScenario.PAYMENT_REMINDER, "order:2", 2L, dueAt.plusDays(1))
        ));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());

        assertThat(affected).isEqualTo(2);
        assertThat(sql.getValue())
                .contains("INSERT INTO scheduled_client_message_state")
                .contains("AS incoming")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("incoming.archive_order_id")
                .contains("scheduled_client_message_state.state_status")
                .contains("state_transaction_outcome_uncertain");
        assertThat(sql.getValue()).doesNotContain("VALUES(");
        assertThat(params.getValue().getValue("targetKey0")).isEqualTo("order:1");
        assertThat(params.getValue().getValue("targetKey1")).isEqualTo("order:2");
    }

    private static StateSeed seed(
            ClientMessageScenario scenario,
            String targetKey,
            Long orderId,
            LocalDateTime dueAt
    ) {
        return new StateSeed(
                scenario,
                ClientMessageTargetType.ORDER,
                targetKey,
                10L,
                orderId,
                null,
                dueAt
        );
    }
}
