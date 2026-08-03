package com.hunt.otziv.client_messages.repository;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ScheduledClientMessageStateBatchRepository {

    private static final int CHUNK_SIZE = 500;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Atomically creates missing states and applies the two safe reconciliation
     * repairs to existing rows. A unique (scenario, target_key) conflict is an
     * expected idempotent path, not an exception.
     */
    public int upsertAll(Collection<StateSeed> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return 0;
        }
        List<StateSeed> values = new ArrayList<>(seeds);
        int affected = 0;
        for (int offset = 0; offset < values.size(); offset += CHUNK_SIZE) {
            affected += upsertChunk(values.subList(offset, Math.min(values.size(), offset + CHUNK_SIZE)));
        }
        return affected;
    }

    private int upsertChunk(List<StateSeed> seeds) {
        StringBuilder sql = new StringBuilder("""
                INSERT INTO scheduled_client_message_state (
                    scenario, target_type, target_key,
                    company_id, order_id, archive_order_id,
                    state_status, next_attempt_at,
                    consecutive_failures, sent_count,
                    created_at, updated_at
                ) VALUES
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (int index = 0; index < seeds.size(); index++) {
            if (index > 0) {
                sql.append(",\n");
            }
            sql.append("(:scenario").append(index)
                    .append(", :targetType").append(index)
                    .append(", :targetKey").append(index)
                    .append(", :companyId").append(index)
                    .append(", :orderId").append(index)
                    .append(", :archiveOrderId").append(index)
                    .append(", 'ACTIVE', :nextAttemptAt").append(index)
                    .append(", 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");

            StateSeed seed = seeds.get(index);
            params.addValue("scenario" + index, seed.scenario().name());
            params.addValue("targetType" + index, seed.targetType().name());
            params.addValue("targetKey" + index, seed.targetKey());
            params.addValue("companyId" + index, seed.companyId());
            params.addValue("orderId" + index, seed.orderId());
            params.addValue("archiveOrderId" + index, seed.archiveOrderId());
            params.addValue("nextAttemptAt" + index, seed.nextAttemptAt());
        }
        sql.append("""

                AS incoming
                ON DUPLICATE KEY UPDATE
                    updated_at = CASE
                        WHEN (
                            scheduled_client_message_state.state_status = 'ACTIVE'
                            AND scheduled_client_message_state.next_attempt_at IS NULL
                            AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (
                                'state_transaction_in_progress',
                                'state_transaction_outcome_uncertain'
                            )
                        ) OR (
                            scheduled_client_message_state.archive_order_id IS NULL
                            AND incoming.archive_order_id IS NOT NULL
                        ) THEN CURRENT_TIMESTAMP(6)
                        ELSE scheduled_client_message_state.updated_at
                    END,
                    next_attempt_at = CASE
                        WHEN scheduled_client_message_state.state_status = 'ACTIVE'
                         AND scheduled_client_message_state.next_attempt_at IS NULL
                         AND LOWER(TRIM(COALESCE(scheduled_client_message_state.last_error_code, ''))) NOT IN (
                             'state_transaction_in_progress',
                             'state_transaction_outcome_uncertain'
                        )
                        THEN incoming.next_attempt_at
                        ELSE scheduled_client_message_state.next_attempt_at
                    END,
                    archive_order_id = COALESCE(
                        scheduled_client_message_state.archive_order_id,
                        incoming.archive_order_id
                    )
                """);
        return jdbc.update(sql.toString(), params);
    }

    public record StateSeed(
            ClientMessageScenario scenario,
            ClientMessageTargetType targetType,
            String targetKey,
            Long companyId,
            Long orderId,
            Long archiveOrderId,
            LocalDateTime nextAttemptAt
    ) {
    }
}
