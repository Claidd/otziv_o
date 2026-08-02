package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.r_review.model.ReviewArchive;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewArchiveRepository extends CrudRepository<ReviewArchive, Long> {
    // Проверка наличия записи с таким же текстом
    @Query(value = """
        SELECT COUNT(*)
        FROM (
            SELECT 1
            FROM reviews_archive r
            WHERE r.review_archive_text_hash = UNHEX(SHA2(COALESCE(:text, ''), 256))
              AND ((:text IS NULL AND r.review_archive_text IS NULL) OR r.review_archive_text = :text)
            LIMIT 1
        ) matched_review_archive
    """, nativeQuery = true)
    long countExistingText(@Param("text") String text);

    default boolean existsByText(String text) {
        return countExistingText(text) > 0;
    }

    @Query(value = """
        SELECT COUNT(*)
        FROM (
            SELECT 1
            FROM reviews_archive r
            WHERE r.review_archive_text_hash = UNHEX(SHA2(COALESCE(:text, ''), 256))
              AND ((:text IS NULL AND r.review_archive_text IS NULL) OR r.review_archive_text = :text)
              AND NOT (
                    (
                        :reviewId IS NOT NULL
                        AND r.review_archive_source_review_id = :reviewId
                        AND r.review_archive_source_reason IN ('BACKFILL', 'ORDER_ARCHIVED')
                    )
                    OR (
                        :orderId IS NOT NULL
                        AND r.review_archive_source_order_id = :orderId
                        AND r.review_archive_source_reason IN ('BACKFILL', 'ORDER_ARCHIVED')
                    )
              )
            LIMIT 1
        ) matched_review_archive
    """, nativeQuery = true)
    long countExistingTextExcludingOwnSource(
            @Param("text") String text,
            @Param("reviewId") Long reviewId,
            @Param("orderId") Long orderId
    );

    default boolean existsByTextExcludingOwnSource(String text, Long reviewId, Long orderId) {
        return countExistingTextExcludingOwnSource(text, reviewId, orderId) > 0;
    }

    @Query(value = """
        SELECT *
        FROM reviews_archive r
        WHERE r.review_archive_text_hash = UNHEX(SHA2(COALESCE(:text, ''), 256))
          AND ((:text IS NULL AND r.review_archive_text IS NULL) OR r.review_archive_text = :text)
        LIMIT 1
    """, nativeQuery = true)
    Optional<ReviewArchive> findFirstByText(@Param("text") String text);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ReviewArchive r
        SET r.category = :category,
            r.subCategory = :subCategory
        WHERE r.text IN :texts
    """)
    int updateClassificationByTexts(
            @Param("texts") List<String> texts,
            @Param("category") Category category,
            @Param("subCategory") SubCategory subCategory
    );

    @Override
    @Query("""
        SELECT r
        FROM ReviewArchive r
        LEFT JOIN FETCH r.category
        LEFT JOIN FETCH r.subCategory
        LEFT JOIN FETCH r.sourceReview
        LEFT JOIN FETCH r.sourceOrder
    """)
    List<ReviewArchive> findAll();

    @Override
    <S extends ReviewArchive> S save(S entity);

}
