package com.hunt.otziv.analytics.service;

import com.hunt.otziv.admin.dto.personal.UserData;
import com.hunt.otziv.admin.service.PersonalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateScoreComparisonService {

    private static final Set<String> SCORE_ROLES = Set.of(
            "ROLE_MANAGER",
            "ROLE_WORKER",
            "ROLE_OPERATOR",
            "ROLE_MARKETOLOG"
    );

    private static final List<ScoreField> COMPARED_FIELDS = List.of(
            new ScoreField("salary", UserData::getSalary, "salaryKopecks", UserData::getSalaryKopecks),
            new ScoreField("totalSum", UserData::getTotalSum),
            new ScoreField("zpTotal", UserData::getZpTotal, "zpTotalKopecks", UserData::getZpTotalKopecks),
            new ScoreField("newCompanies", UserData::getNewCompanies),
            new ScoreField("order1Month", UserData::getOrder1Month),
            new ScoreField("review1Month", UserData::getReview1Month),
            new ScoreField("leadsNew", UserData::getLeadsNew),
            new ScoreField("leadsInWork", UserData::getLeadsInWork),
            new ScoreField("percentInWork", UserData::getPercentInWork)
    );

    private static final List<String> SKIPPED_FIELDS = List.of(
            "newOrders",
            "correctOrders",
            "inVigul",
            "inPublish",
            "imageId",
            "userId"
    );

    private final PersonalService personalService;
    private final AnalyticsAggregateScoreService aggregateScoreService;

    public AnalyticsScoreComparison compare(LocalDate selectedDate) {
        LocalDate date = selectedDate == null ? LocalDate.now() : selectedDate;
        List<UserData> legacyRows = scoreRows(personalService.getPersonalsAndCountToScore(date));
        Optional<List<UserData>> aggregateResult = aggregateScoreService.buildScore(date)
                .map(this::scoreRows);

        if (aggregateResult.isEmpty()) {
            return new AnalyticsScoreComparison(
                    date,
                    false,
                    false,
                    legacyRows.size(),
                    0,
                    0,
                    0,
                    comparedFieldNames(),
                    SKIPPED_FIELDS,
                    List.of()
            );
        }

        List<UserData> aggregateRows = aggregateResult.get();
        Map<String, UserData> legacyByKey = byScoreKey(legacyRows);
        Map<String, UserData> aggregateByKey = byScoreKey(aggregateRows);
        TreeSet<String> keys = new TreeSet<>(legacyByKey.keySet());
        keys.addAll(aggregateByKey.keySet());

        List<ScoreFieldComparison> mismatches = new ArrayList<>();
        int comparedUsers = 0;

        for (String key : keys) {
            UserData legacy = legacyByKey.get(key);
            UserData aggregate = aggregateByKey.get(key);
            if (legacy == null || aggregate == null) {
                mismatches.add(new ScoreFieldComparison(
                        key,
                        "row",
                        presence(legacy),
                        presence(aggregate),
                        "",
                        false
                ));
                continue;
            }

            comparedUsers++;
            for (ScoreField field : COMPARED_FIELDS) {
                Long legacyExact = field.exactValue() == null ? null : field.exactValue().apply(legacy);
                Long aggregateExact = field.exactValue() == null ? null : field.exactValue().apply(aggregate);
                boolean compareExact = legacyExact != null && aggregateExact != null;
                String fieldName = compareExact ? field.exactName() : field.name();
                Long legacyValue = compareExact ? legacyExact : zeroIfNull(field.value().apply(legacy));
                Long aggregateValue = compareExact ? aggregateExact : zeroIfNull(field.value().apply(aggregate));
                if (!legacyValue.equals(aggregateValue)) {
                    mismatches.add(new ScoreFieldComparison(
                            key,
                            fieldName,
                            String.valueOf(legacyValue),
                            String.valueOf(aggregateValue),
                            BigInteger.valueOf(aggregateValue).subtract(BigInteger.valueOf(legacyValue)).toString(),
                            false
                    ));
                }
            }
        }

        return new AnalyticsScoreComparison(
                date,
                true,
                mismatches.isEmpty(),
                legacyRows.size(),
                aggregateRows.size(),
                comparedUsers,
                mismatches.size(),
                comparedFieldNames(),
                SKIPPED_FIELDS,
                mismatches
        );
    }

    private List<UserData> scoreRows(List<UserData> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> SCORE_ROLES.contains(row.getRole()))
                .sorted(Comparator
                        .comparing(UserData::getRole, Comparator.nullsLast(String::compareTo))
                        .thenComparing(UserData::getFio, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private Map<String, UserData> byScoreKey(List<UserData> rows) {
        return rows.stream()
                .collect(Collectors.toMap(
                        this::scoreKey,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private String scoreKey(UserData user) {
        return user.getRole() + "|" + user.getFio();
    }

    private List<String> comparedFieldNames() {
        return COMPARED_FIELDS.stream()
                .flatMap(field -> field.exactName() == null
                        ? java.util.stream.Stream.of(field.name())
                        : java.util.stream.Stream.of(field.exactName(), field.name()))
                .toList();
    }

    private static String presence(UserData user) {
        return user == null ? "missing" : "present";
    }

    private static Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private record ScoreField(String name, Function<UserData, Long> value,
                              String exactName, Function<UserData, Long> exactValue) {
        private ScoreField(String name, Function<UserData, Long> value) {
            this(name, value, null, null);
        }
    }

    public record AnalyticsScoreComparison(
            LocalDate date,
            boolean aggregateAvailable,
            boolean matches,
            int legacyUserCount,
            int aggregateUserCount,
            int comparedUserCount,
            int mismatchCount,
            List<String> comparedFields,
            List<String> skippedFields,
            List<ScoreFieldComparison> mismatches
    ) {
    }

    public record ScoreFieldComparison(
            String userKey,
            String field,
            String legacyValue,
            String aggregateValue,
            String delta,
            boolean matches
    ) {
    }
}
