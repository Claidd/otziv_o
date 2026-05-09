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

import java.time.LocalDate;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "analytics_daily_user",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analytics_daily_user_date_user_role",
                columnNames = {"metric_date", "user_id", "role_name"}
        ),
        indexes = {
                @Index(name = "idx_analytics_daily_user_date", columnList = "metric_date"),
                @Index(name = "idx_analytics_daily_user_user_date", columnList = "user_id, metric_date"),
                @Index(name = "idx_analytics_daily_user_role_date", columnList = "role_name, metric_date"),
                @Index(name = "idx_analytics_daily_user_manager_date", columnList = "manager_user_id, metric_date")
        }
)
public class AnalyticsDailyUser extends AnalyticsUserMetricAggregate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analytics_daily_user_id")
    private Long id;

    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;
}
