package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationEventType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** One current SQL snapshot, with independent aggregates to prevent join multiplication. No balance cache. */
@Repository
@RequiredArgsConstructor
public class ContractorAdminFinancialReadRepository {
    static final String SQL = """
            SELECT e.profile_id, 'ACCRUAL' AS kind, '' AS metric, SUM(e.amount_kopecks) AS total,
                   SUM(CASE WHEN e.occurred_on >= :fromDate AND e.occurred_on < :toDate THEN e.amount_kopecks ELSE 0 END) AS period
            FROM (
                SELECT profile_id, occurred_on, amount_kopecks
                FROM contractor_reward_ledger
                WHERE profile_id IN (:ids) AND active = TRUE
                UNION ALL
                SELECT profile_id, effective_on AS occurred_on, signed_amount_kopecks AS amount_kopecks
                FROM contractor_accrual_adjustments
                WHERE profile_id IN (:ids)
            ) e
            GROUP BY e.profile_id
            UNION ALL
            SELECT profile_id, 'ADJUSTMENT_BUCKET', bucket, SUM(signed_amount_kopecks),
                   SUM(CASE WHEN effective_on >= :fromDate AND effective_on < :toDate
                       THEN signed_amount_kopecks ELSE 0 END)
            FROM contractor_accrual_adjustments
            WHERE profile_id IN (:ids)
            GROUP BY profile_id, bucket
            UNION ALL
            SELECT a.recipient_profile_id, 'EVENT', e.event_type, SUM(e.amount_kopecks),
                   SUM(CASE WHEN e.effective_at >= :fromTime AND e.effective_at < :toTime THEN e.amount_kopecks ELSE 0 END)
            FROM contractor_payment_allocation_events e JOIN contractor_payment_allocations a ON a.id = e.allocation_id
            WHERE a.recipient_profile_id IN (:ids) AND a.mode = :mode AND e.event_type IN (:types)
            GROUP BY a.recipient_profile_id, e.event_type
            UNION ALL
            SELECT a.recipient_profile_id, 'EXPOSURE', a.status,
                   SUM(CASE WHEN a.amount_kopecks > a.confirmed_kopecks - a.returned_kopecks
                       THEN a.amount_kopecks - (a.confirmed_kopecks - a.returned_kopecks) ELSE 0 END), 0
            FROM contractor_payment_allocations a
            WHERE a.recipient_profile_id IN (:ids) AND a.mode = :mode AND a.status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
            GROUP BY a.recipient_profile_id, a.status
            UNION ALL
            SELECT a.actual_recipient_profile_id, 'ACTUAL', '', COUNT(a.id), COALESCE(SUM(a.amount_kopecks), 0)
            FROM contractor_actual_payment_attributions a
            WHERE a.actual_recipient_profile_id IN (:ids) AND a.accounting_mode = :mode
              AND a.effective_at >= :fromTime AND a.effective_at < :toTime
            GROUP BY a.actual_recipient_profile_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    public record Accrual(long total, long month) {}
    /** Net month amounts; deduction signs are inverted, and reversals can make either field negative. */
    public record AdjustmentMonths(long bonusMonthKopecks, long deductionMonthKopecks) {}
    public record ActualTransfers(long count, long amountKopecks) {
        public static ActualTransfers empty() { return new ActualTransfers(0, 0); }
    }
    public record EventTotals(Long profileId, ContractorAllocationEventType type, long total, long month)
            implements ContractorPaymentAllocationEventRepository.ProfileEventTotals {
        public Long getProfileId() { return profileId; }
        public ContractorAllocationEventType getType() { return type; }
        public long getTotal() { return total; }
        public long getMonth() { return month; }
    }
    public record Facts(Map<Long, Accrual> accruals, List<EventTotals> events,
                        Map<Long, Map<ContractorAllocationStatus, Long>> exposures, Map<Long, ActualTransfers> transfers,
                        Map<Long, AdjustmentMonths> adjustmentMonths) {
        public Facts(Map<Long, Accrual> accruals, List<EventTotals> events,
                     Map<Long, Map<ContractorAllocationStatus, Long>> exposures, Map<Long, ActualTransfers> transfers) {
            this(accruals, events, exposures, transfers, Map.of());
        }
        public static Facts empty() { return new Facts(Map.of(), List.of(), Map.of(), Map.of(), Map.of()); }
    }

    @Transactional(readOnly = true)
    public Facts read(Collection<Long> ids, ContractorAllocationMode mode, Collection<ContractorAllocationEventType> types,
                      LocalDate from, LocalDate to) {
        if (ids == null || ids.isEmpty()) return Facts.empty();
        Objects.requireNonNull(mode);
        var params = new MapSqlParameterSource("ids", ids).addValue("mode", mode.name())
                .addValue("types", types.stream().map(Enum::name).toList())
                .addValue("fromDate", from).addValue("toDate", to)
                .addValue("fromTime", from.atStartOfDay()).addValue("toTime", to.atStartOfDay());
        Map<Long, Accrual> accruals = new HashMap<>();
        List<EventTotals> events = new ArrayList<>();
        Map<Long, Map<ContractorAllocationStatus, Long>> exposures = new HashMap<>();
        Map<Long, ActualTransfers> transfers = new HashMap<>();
        Map<Long, AdjustmentMonths> adjustmentMonths = new HashMap<>();
        jdbc.query(SQL, params, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
            long id = row.getLong("profile_id");
            long total = row.getBigDecimal("total").longValueExact();
            long period = row.getBigDecimal("period").longValueExact();
            switch (row.getString("kind")) {
                case "ACCRUAL" -> accruals.put(id, new Accrual(total, period));
                case "ADJUSTMENT_BUCKET" -> {
                    AdjustmentMonths previous = adjustmentMonths.getOrDefault(id, new AdjustmentMonths(0, 0));
                    switch (row.getString("metric")) {
                        case "BONUS" -> adjustmentMonths.put(id, new AdjustmentMonths(
                                Math.addExact(previous.bonusMonthKopecks(), period), previous.deductionMonthKopecks()));
                        case "DEDUCTION" -> adjustmentMonths.put(id, new AdjustmentMonths(
                                previous.bonusMonthKopecks(),
                                Math.addExact(previous.deductionMonthKopecks(), Math.negateExact(period))));
                        default -> throw new IllegalStateException("Unexpected adjustment bucket");
                    }
                }
                case "EVENT" -> events.add(new EventTotals(id, ContractorAllocationEventType.valueOf(row.getString("metric")), total, period));
                case "EXPOSURE" -> exposures.computeIfAbsent(id, ignored -> new EnumMap<>(ContractorAllocationStatus.class))
                        .put(ContractorAllocationStatus.valueOf(row.getString("metric")), total);
                case "ACTUAL" -> transfers.put(id, new ActualTransfers(total, period));
                default -> throw new IllegalStateException("Unexpected financial aggregate kind");
            }
        });
        Map<Long, Map<ContractorAllocationStatus, Long>> immutableExposures = new HashMap<>();
        exposures.forEach((id, amounts) -> immutableExposures.put(id, Map.copyOf(amounts)));
        return new Facts(Map.copyOf(accruals), List.copyOf(events), Map.copyOf(immutableExposures),
                Map.copyOf(transfers), Map.copyOf(adjustmentMonths));
    }
}
