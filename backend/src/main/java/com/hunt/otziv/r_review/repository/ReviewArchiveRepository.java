package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.r_review.model.ReviewArchive;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    @Override
    @Query("""
        SELECT r
        FROM ReviewArchive r
        LEFT JOIN FETCH r.category
        LEFT JOIN FETCH r.subCategory
    """)
    List<ReviewArchive> findAll();

    @Override
    <S extends ReviewArchive> S save(S entity);

}
