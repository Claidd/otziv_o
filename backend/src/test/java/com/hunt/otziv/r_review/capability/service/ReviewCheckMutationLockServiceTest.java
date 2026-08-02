package com.hunt.otziv.r_review.capability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class ReviewCheckMutationLockServiceTest {

    @Test
    void upsertsThenLocksOneValidatedOrderRow() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        OrderAggregateMutationLockService aggregateLock = mock(OrderAggregateMutationLockService.class);
        UUID orderDetailId = UUID.randomUUID();
        when(jdbc.queryForList(
                anyString(),
                any(MapSqlParameterSource.class),
                eq(Long.class)
        )).thenReturn(List.of(11L));

        new ReviewCheckMutationLockService(jdbc, aggregateLock).lock(orderDetailId);

        ArgumentCaptor<String> upsertSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> lockSql = ArgumentCaptor.forClass(String.class);
        var ordered = inOrder(jdbc, aggregateLock);
        ordered.verify(jdbc).update(upsertSql.capture(), any(MapSqlParameterSource.class));
        ordered.verify(jdbc).queryForList(
                lockSql.capture(),
                any(MapSqlParameterSource.class),
                eq(Long.class)
        );
        ordered.verify(aggregateLock).lockIfLive(11L);

        assertThat(upsertSql.getValue()).contains(
                "INSERT INTO review_check_mutation_locks (order_id, created_at)",
                "od.order_detail_id = UUID_TO_BIN(:orderDetailId)",
                "aod.order_detail_id = UUID_TO_BIN(:orderDetailId)",
                "ao.restored_at IS NULL",
                "ON DUPLICATE KEY UPDATE"
        );
        assertThat(lockSql.getValue()).contains(
                "FROM review_check_mutation_locks mutex",
                "EXISTS (",
                "od.order_detail_order = mutex.order_id",
                "aod.order_detail_order = mutex.order_id",
                "ORDER BY mutex.order_id",
                "FOR UPDATE"
        );
    }

    @Test
    void missingLiveAndArchiveResourceIsUniformNotFound() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        OrderAggregateMutationLockService aggregateLock = mock(OrderAggregateMutationLockService.class);
        when(jdbc.queryForList(
                anyString(),
                any(MapSqlParameterSource.class),
                eq(Long.class)
        )).thenReturn(List.of());

        assertThatThrownBy(() -> new ReviewCheckMutationLockService(jdbc, aggregateLock).lock(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        org.mockito.Mockito.verifyNoInteractions(aggregateLock);
    }

    @Test
    void lockRequiresAnExistingCallerTransaction() throws Exception {
        Method method = ReviewCheckMutationLockService.class.getMethod("lock", UUID.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
