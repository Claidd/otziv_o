package com.hunt.otziv.analytics.model;

import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "analytics_daily_total",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analytics_daily_total_date_scope",
                columnNames = {"metric_date", "scope_key"}
        ),
        indexes = {
                @Index(name = "idx_analytics_daily_total_date", columnList = "metric_date"),
                @Index(name = "idx_analytics_daily_total_scope_date", columnList = "scope_key, metric_date"),
                @Index(name = "idx_analytics_daily_total_scope_user_date", columnList = "scope_user_id, metric_date")
        }
)
public class AnalyticsDailyTotal extends AnalyticsMetricAggregate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analytics_daily_total_id")
    private Long id;

    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;

    @Column(name = "scope_key", nullable = false, length = 96)
    private String scopeKey;

    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_user_id")
    private User scopeUser;

    @Column(name = "period_closed", nullable = false)
    private boolean periodClosed;

    @Column(name = "source_user_count", nullable = false)
    private long sourceUserCount;

    @Column(name = "last_rebuilt_at")
    private Instant lastRebuiltAt;
}
