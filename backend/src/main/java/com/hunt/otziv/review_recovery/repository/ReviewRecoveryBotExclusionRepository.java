package com.hunt.otziv.review_recovery.repository;

import com.hunt.otziv.review_recovery.model.ReviewRecoveryBotExclusion;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBotExclusionId;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRecoveryBotExclusionRepository
        extends JpaRepository<ReviewRecoveryBotExclusion, ReviewRecoveryBotExclusionId> {

    @Query("SELECT e.botId FROM ReviewRecoveryBotExclusion e WHERE e.taskId = :taskId")
    Set<Long> findBotIdsByTaskId(@Param("taskId") Long taskId);

    @Modifying
    @Query(value = """
        INSERT IGNORE INTO review_recovery_bot_exclusions
            (review_recovery_task_id, bot_id, reason, created_at)
        VALUES (:taskId, :botId, :reason, CURRENT_TIMESTAMP(6))
        """, nativeQuery = true)
    int insertIgnore(
            @Param("taskId") Long taskId,
            @Param("botId") Long botId,
            @Param("reason") String reason
    );

    @Modifying
    @Query("DELETE FROM ReviewRecoveryBotExclusion e WHERE e.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
