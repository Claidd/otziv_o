package com.hunt.otziv.external_review_checks.repository;

import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckStatus;
import com.hunt.otziv.external_review_checks.model.ReviewExternalCheck;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewExternalCheckRepository extends CrudRepository<ReviewExternalCheck, Long> {

    @Query("""
        SELECT DISTINCT c
        FROM ReviewExternalCheck c
        JOIN FETCH c.review r
        LEFT JOIN FETCH r.orderDetails d
        LEFT JOIN FETCH d.order o
        LEFT JOIN FETCH o.filial
        WHERE c.id = :checkId
    """)
    Optional<ReviewExternalCheck> findByIdForProcessing(@Param("checkId") Long checkId);

    @Query(value = """
        SELECT r.review_id
        FROM reviews r
        LEFT JOIN order_details od ON od.order_detail_id = r.review_order_details
        LEFT JOIN orders o ON o.order_id = od.order_detail_order
        LEFT JOIN filial f ON f.filial_id = o.order_filial
        WHERE r.review_publish = 1
          AND r.review_published_marked_at IS NOT NULL
          AND r.review_published_marked_at <= :threshold
          AND r.review_external_confirm_status <> 'CONFIRMED'
          AND r.review_text IS NOT NULL
          AND TRIM(r.review_text) <> ''
          AND LOWER(TRIM(r.review_text)) NOT LIKE 'текст отзыва%'
          AND LOWER(TRIM(r.review_text)) NOT LIKE 'нужно подставить%'
          AND f.filial_url IS NOT NULL
          AND TRIM(f.filial_url) <> ''
          AND NOT EXISTS (
              SELECT 1
              FROM review_external_checks c
              WHERE c.review_id = r.review_id
          )
        ORDER BY r.review_published_marked_at ASC, r.review_id ASC
        """, nativeQuery = true)
    List<Long> findCandidateReviewIds(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    @Query("""
        SELECT DISTINCT c
        FROM ReviewExternalCheck c
        JOIN FETCH c.review r
        LEFT JOIN FETCH r.orderDetails d
        LEFT JOIN FETCH d.order o
        LEFT JOIN FETCH o.filial
        WHERE c.status IN :statuses
          AND c.checkAfter <= :now
          AND c.attemptCount < :maxAttempts
        ORDER BY c.checkAfter ASC, c.id ASC
    """)
    List<ReviewExternalCheck> findDueChecks(
            @Param("statuses") Collection<ExternalReviewCheckStatus> statuses,
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable
    );
}
