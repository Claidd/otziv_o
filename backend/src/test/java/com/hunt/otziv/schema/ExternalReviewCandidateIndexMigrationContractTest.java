package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.external_review_checks.repository.ReviewExternalCheckRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class ExternalReviewCandidateIndexMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_192__external_review_candidate_scan_index.sql";

    @Test
    void candidateScanGetsAnAdditiveOldestFirstOnlineIndex() throws Exception {
        String sql = new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "ALTER TABLE reviews",
                "ADD INDEX idx_reviews_external_auto_candidates",
                "(review_publish, review_published_marked_at, review_id, review_external_confirm_status)",
                "ALGORITHM=INPLACE",
                "LOCK=NONE"
        );
        assertThat(sql.toLowerCase(Locale.ROOT))
                .doesNotContain("insert ", "update ", "delete ", "drop ");
    }

    @Test
    void candidateQueryKeepsLegacyNullStatusesAndStableOldestFirstOrder()
            throws Exception {
        Query query = ReviewExternalCheckRepository.class
                .getMethod(
                        "findCandidateReviewIds",
                        LocalDateTime.class,
                        Pageable.class
                )
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).contains(
                "r.review_external_confirm_status IS NULL",
                "OR r.review_external_confirm_status <> 'CONFIRMED'",
                "r.review_published_marked_at <= :threshold",
                "LOWER(TRIM(r.review_text)) NOT LIKE 'текст отзыва%'",
                "LOWER(TRIM(r.review_text)) NOT LIKE 'нужно подставить%'",
                "LEFT JOIN filial order_f ON order_f.filial_id = o.order_filial",
                "LEFT JOIN filial review_f ON review_f.filial_id = r.review_filial",
                "NULLIF(TRIM(order_f.filial_url), '')",
                "NULLIF(TRIM(review_f.filial_url), '')",
                "NOT EXISTS (",
                "c.review_id = r.review_id",
                "ORDER BY r.review_published_marked_at ASC, r.review_id ASC"
        ).doesNotContain(
                "COALESCE(r.review_external_confirm_status, '')"
        );
    }
}
