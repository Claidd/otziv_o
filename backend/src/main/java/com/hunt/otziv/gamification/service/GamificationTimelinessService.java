package com.hunt.otziv.gamification.service;

import com.hunt.otziv.gamification.model.GamificationEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

@Service
public class GamificationTimelinessService {

    public static final String ON_TIME = "ON_TIME";
    public static final String SMALL_DELAY = "SMALL_DELAY";
    public static final String LARGE_DELAY = "LARGE_DELAY";
    public static final String TOO_LATE = "TOO_LATE";
    public static final String NO_DEADLINE = "NO_DEADLINE";

    private static final BigDecimal FULL = BigDecimal.valueOf(1.00);
    private static final BigDecimal SMALL = BigDecimal.valueOf(0.70);
    private static final BigDecimal LARGE = BigDecimal.valueOf(0.40);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public void apply(GamificationEvent event, LocalDate plannedDate, LocalDate actualDate) {
        if (event == null) {
            return;
        }
        event.setPlannedDate(plannedDate);
        event.setActualDate(actualDate);

        Timeliness timeliness = evaluate(plannedDate, actualDate);
        event.setDelayDays(timeliness.delayDays());
        event.setTimelinessBucket(timeliness.bucket());
        event.setTimelinessMultiplier(timeliness.multiplier());
    }

    public Timeliness evaluate(LocalDate plannedDate, LocalDate actualDate) {
        if (plannedDate == null || actualDate == null) {
            return new Timeliness(null, NO_DEADLINE, FULL);
        }

        int delayDays = Math.max(0, (int) ChronoUnit.DAYS.between(plannedDate, actualDate));
        if (delayDays == 0) {
            return new Timeliness(0, ON_TIME, FULL);
        }
        if (delayDays <= 2) {
            return new Timeliness(delayDays, SMALL_DELAY, SMALL);
        }
        if (delayDays <= 7) {
            return new Timeliness(delayDays, LARGE_DELAY, LARGE);
        }
        return new Timeliness(delayDays, TOO_LATE, ZERO);
    }

    public int score(int basePoints, BigDecimal multiplier) {
        BigDecimal safeMultiplier = multiplier == null ? FULL : multiplier;
        return BigDecimal.valueOf(Math.max(0, basePoints))
                .multiply(safeMultiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    public record Timeliness(
            Integer delayDays,
            String bucket,
            BigDecimal multiplier
    ) {
    }
}
