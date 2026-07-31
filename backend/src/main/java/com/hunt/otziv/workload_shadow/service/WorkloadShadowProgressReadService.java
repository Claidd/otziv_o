package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowProgressView;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowProjectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadShadowProgressReadService {

    private final WorkloadShadowProjectionRepository repository;

    public Map<Long, Progress> findCurrentProgress(
            Collection<Long> workerIds,
            LocalDate progressDate
    ) {
        if (emptyRequest(workerIds, progressDate)) {
            return Map.of();
        }
        try {
            return map(repository.findCurrentWorkerProgress(workerIds, progressDate));
        } catch (RuntimeException exception) {
            log.warn(
                    "Current workload progress is unavailable for {} workers on {}; legacy progress will be used: {}",
                    workerIds.size(),
                    progressDate,
                    exception.getMessage()
            );
            return Map.of();
        }
    }

    public Map<Long, Progress> findFinalizedProgress(
            Collection<Long> workerIds,
            LocalDate progressDate
    ) {
        if (emptyRequest(workerIds, progressDate)) {
            return Map.of();
        }
        try {
            return map(repository.findFinalizedWorkerProgress(workerIds, progressDate));
        } catch (RuntimeException exception) {
            log.warn(
                    "Final workload progress is unavailable for {} workers on {}; legacy progress will be used: {}",
                    workerIds.size(),
                    progressDate,
                    exception.getMessage()
            );
            return Map.of();
        }
    }

    private boolean emptyRequest(Collection<Long> workerIds, LocalDate progressDate) {
        return workerIds == null || workerIds.isEmpty() || progressDate == null;
    }

    private Map<Long, Progress> map(List<WorkloadShadowProgressView> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, Progress> result = new LinkedHashMap<>();
        rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.getWorkerId() != null)
                .forEach(row -> result.put(row.getWorkerId(), new Progress(
                        nonNegative(row.getCompletedUnits()),
                        nonNegative(row.getEligibleUnits()),
                        nonNegative(row.getLateExcludedUnits()),
                        nonNegative(row.getExternalBlockedUnits()),
                        percent(row.getProgressPercent()),
                        isTrue(row.getReached100()),
                        isTrue(row.getReached100Once()),
                        row.getFirstReached100At(),
                        row.getLastReached100At()
                )));
        return Map.copyOf(result);
    }

    private static long nonNegative(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static int percent(BigDecimal value) {
        if (value == null) {
            return 0;
        }
        return value.max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private static boolean isTrue(Long value) {
        return value != null && value > 0;
    }

    public record Progress(
            long completed,
            long eligible,
            long lateExcluded,
            long externalBlocked,
            int percent,
            boolean reached100,
            boolean reached100Once,
            java.time.LocalDateTime firstReached100At,
            java.time.LocalDateTime lastReached100At
    ) {
    }
}
