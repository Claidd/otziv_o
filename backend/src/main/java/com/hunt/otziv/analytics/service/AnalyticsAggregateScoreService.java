package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.presonal.UserData;
import com.hunt.otziv.analytics.model.AnalyticsUserMetricAggregate;
import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateScoreService {

    private static final Set<String> SCORE_ROLES = Set.of(
            "ROLE_MANAGER",
            "ROLE_WORKER",
            "ROLE_OPERATOR",
            "ROLE_MARKETOLOG"
    );
    private static final Set<String> LEAD_SCORE_ROLES = Set.of("ROLE_OPERATOR", "ROLE_MARKETOLOG");
    private static final long DEFAULT_IMAGE_ID = 1L;

    private final AnalyticsAggregateReadService readService;

    public Optional<List<UserData>> buildScore(LocalDate selectedDate) {
        if (selectedDate == null) {
            return Optional.empty();
        }

        LocalDate monthStart = selectedDate.withDayOfMonth(1);
        LocalDate monthEnd = selectedDate.withDayOfMonth(selectedDate.lengthOfMonth());
        List<? extends AnalyticsUserMetricAggregate> sourceRows = aggregateRows(monthStart, monthEnd);
        if (sourceRows.isEmpty()) {
            return Optional.empty();
        }

        Map<Long, ScoreAccumulator> users = new LinkedHashMap<>();
        for (AnalyticsUserMetricAggregate row : sourceRows) {
            if (row.getUser() == null || row.getUser().getId() == null) {
                continue;
            }
            users.computeIfAbsent(row.getUser().getId(), ignored -> new ScoreAccumulator(row.getUser(), row.getRoleName()))
                    .add(row);
        }

        long zpTotal = users.values().stream()
                .mapToLong(ScoreAccumulator::salary)
                .sum();

        List<UserData> score = users.values().stream()
                .filter(user -> SCORE_ROLES.contains(user.role()))
                .sorted(Comparator
                        .comparingInt((ScoreAccumulator user) -> rolePriority(user.role()))
                        .thenComparing(ScoreAccumulator::fio, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(user -> user.toUserData(zpTotal))
                .toList();

        return Optional.of(score);
    }

    private List<? extends AnalyticsUserMetricAggregate> aggregateRows(LocalDate monthStart, LocalDate monthEnd) {
        List<? extends AnalyticsUserMetricAggregate> monthlyRows = readService.monthlyUsers(monthStart, monthStart);
        if (!monthlyRows.isEmpty()) {
            return monthlyRows;
        }
        return readService.dailyUsers(monthStart, monthEnd);
    }

    private static int rolePriority(String role) {
        return switch (role == null ? "" : role) {
            case "ROLE_MANAGER" -> 1;
            case "ROLE_WORKER" -> 2;
            case "ROLE_OPERATOR" -> 3;
            case "ROLE_MARKETOLOG" -> 4;
            default -> 5;
        };
    }

    private static long toLong(BigDecimal value) {
        return value == null ? 0L : value.longValue();
    }

    private static class ScoreAccumulator {
        private final User user;
        private final String role;
        private long salary;
        private long payment;
        private long newCompanies;
        private long salaryEntries;
        private long salaryReviews;
        private long leadsNew;
        private long leadsInWork;

        private ScoreAccumulator(User user, String role) {
            this.user = user;
            this.role = role;
        }

        private void add(AnalyticsUserMetricAggregate row) {
            salary += toLong(row.getSalarySum());
            payment += toLong(row.getPaymentSum());
            newCompanies += row.getNewCompaniesCount();
            salaryEntries += row.getSalaryEntryCount();
            salaryReviews += row.getSalaryReviewCount();
            if (LEAD_SCORE_ROLES.contains(role)) {
                leadsNew += row.getLeadsNewCount();
                leadsInWork += row.getLeadsInWorkCount();
            }
        }

        private String fio() {
            return user.getFio();
        }

        private String role() {
            return role;
        }

        private long salary() {
            return salary;
        }

        private UserData toUserData(long zpTotal) {
            long percentInWork = leadsNew == 0 ? 0 : (leadsInWork * 100) / leadsNew;
            return new UserData(
                    user.getFio(),
                    role,
                    salary,
                    payment,
                    zpTotal,
                    newCompanies,
                    0L,
                    0L,
                    0L,
                    0L,
                    imageId(user),
                    user.getId(),
                    salaryEntries,
                    salaryReviews,
                    leadsNew,
                    leadsInWork,
                    percentInWork,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L
            );
        }

        private static Long imageId(User user) {
            Image image = user.getImage();
            return image == null || image.getId() == null ? DEFAULT_IMAGE_ID : image.getId();
        }
    }
}
