package com.hunt.otziv.analytics.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateSourceRangeService {

    public static final LocalDate MINIMUM_SOURCE_DATE = LocalDate.of(2023, 1, 1);
    public static final LocalDate MAXIMUM_SOURCE_DATE = LocalDate.of(2027, 12, 31);

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<AnalyticsSourceRange> findSourceRange() {
        return jdbc.query("""
                WITH source_ranges AS (
                    SELECT MIN(z.zp_date) AS first_date, MAX(z.zp_date) AS last_date
                    FROM zp z
                    WHERE z.zp_date BETWEEN :minimumSourceDate AND :maximumSourceDate

                    UNION ALL

                    SELECT MIN(pc.check_date) AS first_date, MAX(pc.check_date) AS last_date
                    FROM payment_check pc
                    WHERE pc.check_date BETWEEN :minimumSourceDate AND :maximumSourceDate
                      AND pc.check_active = 1

                    UNION ALL

                    SELECT MIN(c.create_date) AS first_date, MAX(c.create_date) AS last_date
                    FROM companies c
                    WHERE c.create_date BETWEEN :minimumSourceDate AND :maximumSourceDate

                    UNION ALL

                    SELECT MIN(l.create_date) AS first_date, MAX(l.create_date) AS last_date
                    FROM leads l
                    WHERE l.create_date BETWEEN :minimumSourceDate AND :maximumSourceDate

                    UNION ALL

                    SELECT MIN(r.review_publish_date) AS first_date, MAX(r.review_publish_date) AS last_date
                    FROM reviews r
                    WHERE r.review_publish_date BETWEEN :minimumSourceDate AND :maximumSourceDate
                      AND r.review_publish = 1
                )
                SELECT MIN(first_date) AS first_date, MAX(last_date) AS last_date
                FROM source_ranges
                """, Map.of(
                        "minimumSourceDate", MINIMUM_SOURCE_DATE,
                        "maximumSourceDate", MAXIMUM_SOURCE_DATE
                ), sourceRangeMapper())
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    private RowMapper<AnalyticsSourceRange> sourceRangeMapper() {
        return (rs, rowNum) -> {
            LocalDate firstDate = toLocalDate(rs.getObject("first_date"));
            LocalDate lastDate = toLocalDate(rs.getObject("last_date"));
            if (firstDate == null || lastDate == null) {
                return null;
            }

            return new AnalyticsSourceRange(
                    firstDate,
                    lastDate,
                    AnalyticsAggregateReadService.monthStart(firstDate),
                    AnalyticsAggregateReadService.monthStart(lastDate)
            );
        };
    }

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }

    public record AnalyticsSourceRange(
            LocalDate firstDate,
            LocalDate lastDate,
            LocalDate firstMonth,
            LocalDate lastMonth
    ) {
    }
}
