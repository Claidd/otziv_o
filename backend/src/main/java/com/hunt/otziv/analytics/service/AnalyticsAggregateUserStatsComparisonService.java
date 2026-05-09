package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateUserStatsComparisonService {

    private static final List<String> COMPARED_FIELDS = List.of(
            "id",
            "fio",
            "imageId",
            "coefficient",
            "zpPayMap",
            "zpPayMapMonth",
            "sum1Day",
            "sum1Week",
            "sum1Month",
            "sum1Year",
            "sumOrders1Month",
            "sumOrders2Month",
            "percent1Day",
            "percent1Week",
            "percent1Month",
            "percent1Year",
            "percent1MonthOrders",
            "percent2MonthOrders"
    );

    private static final List<String> SKIPPED_FIELDS = List.of(
            "percentNoPay",
            "avgPublish1Day",
            "reviewsGet1Day",
            "reviewsGetWeek",
            "reviewsGetMonth",
            "reviewsGetYear",
            "reviewsPublish1Day",
            "reviewsPublishWeek",
            "reviewsPublishMonth",
            "reviewsPublishYear",
            "reviewsPublished1Day",
            "reviewsPublishedWeek",
            "reviewsPublishedMonth",
            "reviewsPublishedYear",
            "reviewsPay1Day",
            "reviewsPayWeek",
            "reviewsPayMonth",
            "reviewsPayYear"
    );

    private final PersonalService personalService;
    private final UserService userService;
    private final AnalyticsAggregateUserStatsService aggregateUserStatsService;
    private final ObjectMapper objectMapper;

    public AnalyticsUserStatsComparison compare(Long userId, LocalDate selectedDate) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must not be null");
        }
        if (selectedDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must not be null");
        }

        User user = userService.findByIdToUserInfo(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        UserStatDTO legacy = personalService.getWorkerReviews(user, selectedDate);
        Optional<UserStatDTO> aggregate = aggregateUserStatsService.buildUserStats(selectedDate, user);
        if (aggregate.isEmpty()) {
            return new AnalyticsUserStatsComparison(
                    selectedDate,
                    user.getUsername(),
                    user.getId(),
                    false,
                    false,
                    COMPARED_FIELDS,
                    SKIPPED_FIELDS,
                    List.of()
            );
        }

        List<FieldComparison> fields = compareFields(legacy, aggregate.get());
        return new AnalyticsUserStatsComparison(
                selectedDate,
                user.getUsername(),
                user.getId(),
                true,
                fields.stream().allMatch(FieldComparison::matches),
                COMPARED_FIELDS,
                SKIPPED_FIELDS,
                fields
        );
    }

    private List<FieldComparison> compareFields(UserStatDTO legacy, UserStatDTO aggregate) {
        List<FieldComparison> result = new ArrayList<>();

        compareObject(result, "id", legacy.getId(), aggregate.getId());
        compareObject(result, "fio", legacy.getFio(), aggregate.getFio());
        compareObject(result, "imageId", legacy.getImageId(), aggregate.getImageId());
        compareBigDecimal(result, "coefficient", legacy.getCoefficient(), aggregate.getCoefficient());
        compareJson(result, "zpPayMap", legacy.getZpPayMap(), aggregate.getZpPayMap());
        compareJson(result, "zpPayMapMonth", legacy.getZpPayMapMonth(), aggregate.getZpPayMapMonth());

        compareInt(result, "sum1Day", legacy, aggregate, UserStatDTO::getSum1Day);
        compareInt(result, "sum1Week", legacy, aggregate, UserStatDTO::getSum1Week);
        compareInt(result, "sum1Month", legacy, aggregate, UserStatDTO::getSum1Month);
        compareInt(result, "sum1Year", legacy, aggregate, UserStatDTO::getSum1Year);
        compareInt(result, "sumOrders1Month", legacy, aggregate, UserStatDTO::getSumOrders1Month);
        compareInt(result, "sumOrders2Month", legacy, aggregate, UserStatDTO::getSumOrders2Month);
        compareInt(result, "percent1Day", legacy, aggregate, UserStatDTO::getPercent1Day);
        compareInt(result, "percent1Week", legacy, aggregate, UserStatDTO::getPercent1Week);
        compareInt(result, "percent1Month", legacy, aggregate, UserStatDTO::getPercent1Month);
        compareInt(result, "percent1Year", legacy, aggregate, UserStatDTO::getPercent1Year);
        compareInt(result, "percent1MonthOrders", legacy, aggregate, UserStatDTO::getPercent1MonthOrders);
        compareInt(result, "percent2MonthOrders", legacy, aggregate, UserStatDTO::getPercent2MonthOrders);

        return result;
    }

    private void compareInt(
            List<FieldComparison> result,
            String field,
            UserStatDTO legacy,
            UserStatDTO aggregate,
            ToIntFunction<UserStatDTO> getter
    ) {
        int legacyValue = getter.applyAsInt(legacy);
        int aggregateValue = getter.applyAsInt(aggregate);
        result.add(new FieldComparison(
                field,
                String.valueOf(legacyValue),
                String.valueOf(aggregateValue),
                String.valueOf(aggregateValue - legacyValue),
                legacyValue == aggregateValue
        ));
    }

    private void compareObject(List<FieldComparison> result, String field, Object legacyValue, Object aggregateValue) {
        boolean matches = Objects.equals(legacyValue, aggregateValue);
        result.add(new FieldComparison(
                field,
                String.valueOf(legacyValue),
                String.valueOf(aggregateValue),
                matches ? "0" : "differs",
                matches
        ));
    }

    private void compareBigDecimal(List<FieldComparison> result, String field, BigDecimal legacyValue, BigDecimal aggregateValue) {
        boolean matches = compareNullableBigDecimal(legacyValue, aggregateValue);
        String delta = "0";
        if (legacyValue != null && aggregateValue != null) {
            delta = aggregateValue.subtract(legacyValue).stripTrailingZeros().toPlainString();
        } else if (!matches) {
            delta = "differs";
        }
        result.add(new FieldComparison(
                field,
                legacyValue == null ? "null" : legacyValue.stripTrailingZeros().toPlainString(),
                aggregateValue == null ? "null" : aggregateValue.stripTrailingZeros().toPlainString(),
                delta,
                matches
        ));
    }

    private boolean compareNullableBigDecimal(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private void compareJson(List<FieldComparison> result, String field, String legacyValue, String aggregateValue) {
        JsonNode legacyJson = parseJson(field, legacyValue);
        JsonNode aggregateJson = parseJson(field, aggregateValue);
        boolean matches = jsonEquivalent(legacyJson, aggregateJson);
        result.add(new FieldComparison(
                field,
                compactJson(legacyJson),
                compactJson(aggregateJson),
                matches ? "0" : "json differs",
                matches
        ));
    }

    private boolean jsonEquivalent(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            Set<String> leftFields = iterableToSet(left.fieldNames());
            Set<String> rightFields = iterableToSet(right.fieldNames());
            if (!Objects.equals(leftFields, rightFields)) {
                return false;
            }
            return leftFields.stream()
                    .allMatch(field -> jsonEquivalent(left.get(field), right.get(field)));
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!jsonEquivalent(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private Set<String> iterableToSet(java.util.Iterator<String> iterator) {
        Set<String> values = new LinkedHashSet<>();
        iterator.forEachRemaining(values::add);
        return values;
    }

    private JsonNode parseJson(String field, String value) {
        try {
            return value == null || value.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(value);
        } catch (RuntimeException | java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid JSON in " + field, exception);
        }
    }

    private String compactJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException | java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize comparison JSON", exception);
        }
    }

    public record AnalyticsUserStatsComparison(
            LocalDate date,
            String username,
            Long userId,
            boolean aggregateAvailable,
            boolean matches,
            List<String> comparedFields,
            List<String> skippedFields,
            List<FieldComparison> fields
    ) {
    }

    public record FieldComparison(
            String field,
            String legacyValue,
            String aggregateValue,
            String delta,
            boolean matches
    ) {
    }
}
