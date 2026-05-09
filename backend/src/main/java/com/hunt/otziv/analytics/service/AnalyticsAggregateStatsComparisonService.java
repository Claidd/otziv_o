package com.hunt.otziv.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsAggregateStatsComparisonService {

    private final PersonalService personalService;
    private final UserService userService;
    private final AnalyticsAggregateStatsService aggregateStatsService;
    private final ObjectMapper objectMapper;

    public AnalyticsStatsComparison compare(String username, LocalDate selectedDate, String requestedRole) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username must not be blank");
        }
        if (selectedDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must not be null");
        }

        User user = userService.findByUserName(username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String role = resolveRole(user, requestedRole);

        StatDTO legacy = personalService.getStats(selectedDate, user, role);
        Optional<StatDTO> aggregate = aggregateStatsService.buildStats(
                selectedDate,
                user,
                role,
                AnalyticsAggregateStatsService.defaultChartFrom(selectedDate),
                selectedDate
        );
        if (aggregate.isEmpty()) {
            return new AnalyticsStatsComparison(
                    selectedDate,
                    user.getUsername(),
                    user.getId(),
                    role,
                    false,
                    false,
                    List.of()
            );
        }

        List<FieldComparison> fields = compareFields(legacy, aggregate.get());
        return new AnalyticsStatsComparison(
                selectedDate,
                user.getUsername(),
                user.getId(),
                role,
                true,
                fields.stream().allMatch(FieldComparison::matches),
                fields
        );
    }

    private String resolveRole(User user, String requestedRole) {
        if (requestedRole != null && !requestedRole.isBlank()) {
            String normalized = requestedRole.trim().toUpperCase();
            return normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
        }

        return user.getRoles().stream()
                .map(Role::getAuthority)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "User role not found"));
    }

    private List<FieldComparison> compareFields(StatDTO legacy, StatDTO aggregate) {
        List<FieldComparison> result = new ArrayList<>();
        compareJson(result, "zpPayMap", legacy.getZpPayMap(), aggregate.getZpPayMap());
        compareJson(result, "zpPayMapMonth", legacy.getZpPayMapMonth(), aggregate.getZpPayMapMonth());
        compareJson(result, "orderPayMap", legacy.getOrderPayMap(), aggregate.getOrderPayMap());
        compareJson(result, "orderPayMapMonth", legacy.getOrderPayMapMonth(), aggregate.getOrderPayMapMonth());

        compareInt(result, "sum1DayPay", legacy, aggregate, StatDTO::getSum1DayPay);
        compareInt(result, "sum1WeekPay", legacy, aggregate, StatDTO::getSum1WeekPay);
        compareInt(result, "sum1MonthPay", legacy, aggregate, StatDTO::getSum1MonthPay);
        compareInt(result, "sum1YearPay", legacy, aggregate, StatDTO::getSum1YearPay);
        compareInt(result, "sumOrders1MonthPay", legacy, aggregate, StatDTO::getSumOrders1MonthPay);
        compareInt(result, "sumOrders2MonthPay", legacy, aggregate, StatDTO::getSumOrders2MonthPay);
        compareInt(result, "newLeads", legacy, aggregate, StatDTO::getNewLeads);
        compareInt(result, "leadsInWork", legacy, aggregate, StatDTO::getLeadsInWork);
        compareInt(result, "percent1DayPay", legacy, aggregate, StatDTO::getPercent1DayPay);
        compareInt(result, "percent1WeekPay", legacy, aggregate, StatDTO::getPercent1WeekPay);
        compareInt(result, "percent1MonthPay", legacy, aggregate, StatDTO::getPercent1MonthPay);
        compareInt(result, "percent1YearPay", legacy, aggregate, StatDTO::getPercent1YearPay);
        compareInt(result, "percent1MonthOrdersPay", legacy, aggregate, StatDTO::getPercent1MonthOrdersPay);
        compareInt(result, "percent2MonthOrdersPay", legacy, aggregate, StatDTO::getPercent2MonthOrdersPay);
        compareInt(result, "percent1NewLeadsPay", legacy, aggregate, StatDTO::getPercent1NewLeadsPay);
        compareInt(result, "percent2InWorkLeadsPay", legacy, aggregate, StatDTO::getPercent2InWorkLeadsPay);

        compareInt(result, "sum1Day", legacy, aggregate, StatDTO::getSum1Day);
        compareInt(result, "sum1Week", legacy, aggregate, StatDTO::getSum1Week);
        compareInt(result, "sum1Month", legacy, aggregate, StatDTO::getSum1Month);
        compareInt(result, "sum1Year", legacy, aggregate, StatDTO::getSum1Year);
        compareInt(result, "sumOrders1Month", legacy, aggregate, StatDTO::getSumOrders1Month);
        compareInt(result, "sumOrders2Month", legacy, aggregate, StatDTO::getSumOrders2Month);
        compareInt(result, "percent1Day", legacy, aggregate, StatDTO::getPercent1Day);
        compareInt(result, "percent1Week", legacy, aggregate, StatDTO::getPercent1Week);
        compareInt(result, "percent1Month", legacy, aggregate, StatDTO::getPercent1Month);
        compareInt(result, "percent1Year", legacy, aggregate, StatDTO::getPercent1Year);
        compareInt(result, "percent1MonthOrders", legacy, aggregate, StatDTO::getPercent1MonthOrders);
        compareInt(result, "percent2MonthOrders", legacy, aggregate, StatDTO::getPercent2MonthOrders);
        return List.copyOf(result);
    }

    private void compareInt(
            List<FieldComparison> result,
            String field,
            StatDTO legacy,
            StatDTO aggregate,
            ToIntFunction<StatDTO> getter
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

    public record AnalyticsStatsComparison(
            LocalDate date,
            String username,
            Long userId,
            String role,
            boolean aggregateAvailable,
            boolean matches,
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
