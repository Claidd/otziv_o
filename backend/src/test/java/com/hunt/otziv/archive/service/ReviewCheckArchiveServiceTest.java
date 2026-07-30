package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewCheckArchiveServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private OrderArchiveRestoreService restoreService;

    @Mock
    private ResultSet resultSet;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void terminalPaidArchiveCannotBeRestoredThroughOldReviewLink() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(resultSet.getLong("order_id")).thenReturn(9533L);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getBoolean("terminal_paid_order")).thenReturn(true);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        ReviewCheckArchiveService service = new ReviewCheckArchiveService(jdbc, restoreService);

        assertThatThrownBy(() -> service.restoreByOrderDetailId(
                orderDetailId,
                "Коррекция",
                "anonymous-review-check"
        ))
                .isInstanceOf(ArchiveRestoreConflictException.class)
                .hasMessage("Оплаченный или завершенный архивный заказ доступен по старой ссылке только для просмотра");

        verifyNoInteractions(restoreService);
    }
}
