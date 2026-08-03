package com.hunt.otziv.archive;

import com.hunt.otziv.archive.dto.ArchiveAccessScope;
import com.hunt.otziv.archive.dto.ArchiveReviewRecoverySource;
import com.hunt.otziv.archive.repository.ManagerArchiveRepository;
import com.hunt.otziv.security.credentials.CredentialCipher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
    @Mock
    private CredentialCipher credentialCipher;
    @Mock
    private ResultSet resultSet;

    @Test
    void findOrdersKeepsSpaceBetweenSortColumnAndDirection() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        ManagerArchiveRepository repository = new ManagerArchiveRepository(jdbc, credentialCipher);

        repository.findOrders(ArchiveAccessScope.all(), "all", "", 0, 10, "desc");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        String sql = sqlCaptor.getValue();

        assertEquals(2, countOccurrences(sql, "ORDER BY sort_at DESC, order_id DESC"));
        assertFalse(sql.contains("sort_atDESC"));
        assertTrue(sql.contains("LIMIT :limit OFFSET :offset"));
    }

    @Test
    void findReviewRecoverySourceDecryptsPasswordReadThroughJdbc() throws SQLException {
        when(resultSet.getString(anyString())).thenAnswer(invocation ->
                "bot_password".equals(invocation.getArgument(0)) ? "enc:v1:primary:ciphertext" : null
        );
        when(credentialCipher.decrypt("enc:v1:primary:ciphertext")).thenReturn("plain-password");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        ManagerArchiveRepository repository = new ManagerArchiveRepository(jdbc, credentialCipher);

        ArchiveReviewRecoverySource source = repository.findReviewRecoverySource(10L, 20L).orElseThrow();

        assertEquals("plain-password", source.botPassword());
        verify(credentialCipher).decrypt("enc:v1:primary:ciphertext");
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
