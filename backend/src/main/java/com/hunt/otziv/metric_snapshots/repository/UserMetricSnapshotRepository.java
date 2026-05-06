package com.hunt.otziv.metric_snapshots.repository;

import com.hunt.otziv.metric_snapshots.model.UserMetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UserMetricSnapshotRepository extends JpaRepository<UserMetricSnapshot, Long> {

    List<UserMetricSnapshot> findByUserIdAndPageCode(Long userId, String pageCode);

    @Modifying
    @Query(value = """
            INSERT INTO user_metric_snapshots (
                user_id,
                page_code,
                metric_section,
                metric_status,
                last_seen_value,
                created_at,
                updated_at
            ) VALUES (
                :userId,
                :pageCode,
                :metricSection,
                :metricStatus,
                :lastSeenValue,
                :now,
                :now
            )
            ON DUPLICATE KEY UPDATE
                user_metric_snapshot_id = user_metric_snapshot_id
            """, nativeQuery = true)
    void insertBaselineIfAbsent(
            @Param("userId") Long userId,
            @Param("pageCode") String pageCode,
            @Param("metricSection") String metricSection,
            @Param("metricStatus") String metricStatus,
            @Param("lastSeenValue") int lastSeenValue,
            @Param("now") Instant now
    );

    @Modifying
    @Query(value = """
            INSERT INTO user_metric_snapshots (
                user_id,
                page_code,
                metric_section,
                metric_status,
                last_seen_value,
                created_at,
                updated_at
            ) VALUES (
                :userId,
                :pageCode,
                :metricSection,
                :metricStatus,
                :lastSeenValue,
                :now,
                :now
            )
            ON DUPLICATE KEY UPDATE
                last_seen_value = VALUES(last_seen_value),
                updated_at = VALUES(updated_at)
            """, nativeQuery = true)
    void upsertSeenValue(
            @Param("userId") Long userId,
            @Param("pageCode") String pageCode,
            @Param("metricSection") String metricSection,
            @Param("metricStatus") String metricStatus,
            @Param("lastSeenValue") int lastSeenValue,
            @Param("now") Instant now
    );
}
