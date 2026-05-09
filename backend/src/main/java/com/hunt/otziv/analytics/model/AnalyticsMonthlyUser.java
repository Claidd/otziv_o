package com.hunt.otziv.analytics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
        name = "analytics_monthly_user",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analytics_monthly_user_month_user_role",
                columnNames = {"month_start", "user_id", "role_name"}
        ),
        indexes = {
                @Index(name = "idx_analytics_monthly_user_month", columnList = "month_start"),
                @Index(name = "idx_analytics_monthly_user_user_month", columnList = "user_id, month_start"),
                @Index(name = "idx_analytics_monthly_user_role_month", columnList = "role_name, month_start"),
                @Index(name = "idx_analytics_monthly_user_manager_month", columnList = "manager_user_id, month_start")
        }
)
public class AnalyticsMonthlyUser extends AnalyticsUserMetricAggregate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analytics_monthly_user_id")
    private Long id;

    @Column(name = "month_start", nullable = false)
    private LocalDate monthStart;

    @Column(name = "period_closed", nullable = false)
    private boolean periodClosed;

    @Column(name = "source_days_count", nullable = false)
    private int sourceDaysCount;

    @Column(name = "last_rebuilt_at")
    private Instant lastRebuiltAt;
}
