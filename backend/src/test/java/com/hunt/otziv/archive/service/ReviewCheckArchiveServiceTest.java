package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
    void archivedLookupLoadsCurrentCompanyCommentsForLaterAuthorization() {
        AtomicReference<String> querySql = new AtomicReference<>();
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenAnswer(invocation -> {
            querySql.set(invocation.getArgument(0));
            return List.of();
        });

        ReviewCheckArchiveService service = new ReviewCheckArchiveService(jdbc, restoreService);

        assertThat(service.findByOrderDetailId(UUID.randomUUID())).isEmpty();
        assertThat(querySql.get())
                .contains("COALESCE(c.company_comments, '') AS company_comments");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void archivedReviewsExposeReviewFilialAndQueryFallsBackToArchivedOrderFilial() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        ResultSet baseResultSet = mock(ResultSet.class);
        ResultSet reviewResultSet = mock(ResultSet.class);
        AtomicReference<String> reviewQuerySql = new AtomicReference<>();

        when(baseResultSet.getString(anyString())).thenAnswer(invocation ->
                "order_detail_uuid".equals(invocation.getArgument(0)) ? orderDetailId.toString() : null
        );
        when(baseResultSet.wasNull()).thenReturn(false);
        when(reviewResultSet.getLong("review_id")).thenReturn(501L);
        when(reviewResultSet.getString(anyString())).thenAnswer(invocation ->
                "filial_title".equals(invocation.getArgument(0)) ? "Филиал отзыва" : null
        );
        when(reviewResultSet.wasNull()).thenReturn(false);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper mapper = invocation.getArgument(2);
            if (sql.contains("FROM archive_reviews ar")) {
                reviewQuerySql.set(sql);
                return List.of(mapper.mapRow(reviewResultSet, 0));
            }
            return List.of(mapper.mapRow(baseResultSet, 0));
        });

        ReviewCheckArchiveService service = new ReviewCheckArchiveService(jdbc, restoreService);

        ReviewCheckArchiveService.ArchivedReviewCheck archived = service
                .findByOrderDetailId(orderDetailId)
                .orElseThrow();

        assertThat(archived.reviews()).singleElement()
                .satisfies(review -> assertThat(review.filialTitle()).isEqualTo("Филиал отзыва"));
        assertThat(reviewQuerySql.get())
                .contains("LEFT JOIN filial review_filial ON review_filial.filial_id = ar.review_filial")
                .contains("NULLIF(TRIM(ar.review_filial_title_snapshot), '')")
                .contains("NULLIF(TRIM(review_filial.filial_title), '')")
                .contains("NULLIF(TRIM(ao.filial_title_snapshot), '')")
                .contains("WHEN ar.review_filial IS NULL OR ar.review_filial = ao.order_filial")
                .contains("ELSE NULL");
    }

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
