package com.hunt.otziv.r_review.bot.repository;

import com.hunt.otziv.r_review.bot.model.ReviewBotAssignmentExclusion;
import com.hunt.otziv.r_review.bot.model.ReviewBotAssignmentExclusionId;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewBotAssignmentExclusionRepository extends JpaRepository<ReviewBotAssignmentExclusion, ReviewBotAssignmentExclusionId> {

    @Query("SELECT e.botId FROM ReviewBotAssignmentExclusion e WHERE e.reviewId = :reviewId")
    Set<Long> findBotIdsByReviewId(@Param("reviewId") Long reviewId);

    @Modifying
    @Query(value = """
        INSERT IGNORE INTO review_bot_assignment_exclusions (review_id, bot_id, reason, created_at)
        VALUES (:reviewId, :botId, :reason, CURRENT_TIMESTAMP(6))
        """, nativeQuery = true)
    int insertIgnore(
            @Param("reviewId") Long reviewId,
            @Param("botId") Long botId,
            @Param("reason") String reason
    );

    @Modifying
    @Query("DELETE FROM ReviewBotAssignmentExclusion e WHERE e.reviewId = :reviewId")
    int deleteByReviewId(@Param("reviewId") Long reviewId);

    @Modifying
    @Query(value = """
        DELETE e
        FROM review_bot_assignment_exclusions e
        JOIN reviews r ON r.review_id = e.review_id
        WHERE r.review_publish = 1
          AND COALESCE(
                r.review_published_marked_at,
                TIMESTAMP(r.review_changed),
                TIMESTAMP(r.review_created)
              ) < :cutoff
        """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);
}
