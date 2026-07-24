package com.hunt.otziv.worker_performance.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record TeamPatternAnalysisResponse(
        boolean visible,
        LocalDate from,
        LocalDate to,
        String confidence,
        int workerCount,
        long publicationCount,
        List<PatternInsight> insights,
        Map<Long, WorkerPattern> workers
) {
    public static TeamPatternAnalysisResponse empty(LocalDate from, LocalDate to) {
        return new TeamPatternAnalysisResponse(
                true,
                from,
                to,
                "INSUFFICIENT",
                0,
                0,
                List.of(new PatternInsight(
                        "NOT_ENOUGH_DATA",
                        "NEUTRAL",
                        "INSUFFICIENT",
                        "Недостаточно данных",
                        "Для поиска закономерностей нужны публикации и рабочие события за выбранный период."
                )),
                Map.of()
        );
    }

    public record PatternInsight(
            String code,
            String tone,
            String confidence,
            String title,
            String message
    ) {
    }

    public record WorkerPattern(
            Long userId,
            long publicationCount,
            long blockedAccountCount,
            long recoveryCount,
            long networkEpisodeCount,
            double blockRate,
            double recoveryRate,
            double networkRate,
            double teamMedianBlockRate,
            double teamMedianRecoveryRate,
            double teamMedianNetworkRate,
            String confidence,
            List<PatternInsight> insights
    ) {
    }
}
