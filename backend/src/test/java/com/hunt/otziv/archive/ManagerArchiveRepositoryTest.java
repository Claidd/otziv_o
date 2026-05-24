package com.hunt.otziv.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerArchiveRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void findOrdersKeepsSpaceBetweenSortColumnAndDirection() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        ManagerArchiveRepository repository = new ManagerArchiveRepository(jdbc);

        repository.findOrders(ArchiveAccessScope.all(), "all", "", 0, 10, "desc");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        String sql = sqlCaptor.getValue();

        assertEquals(2, countOccurrences(sql, "ORDER BY sort_at DESC, order_id DESC"));
        assertFalse(sql.contains("sort_atDESC"));
        assertTrue(sql.contains("LIMIT :limit OFFSET :offset"));
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = text.indexOf(pattern);
        while (index >= 0) {
            count++;
            index = text.indexOf(pattern, index + pattern.length());
        }
        return count;
    }
}
