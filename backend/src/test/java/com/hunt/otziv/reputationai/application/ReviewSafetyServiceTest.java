package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.domain.ReviewSafetyReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSafetyServiceTest {

    private final ReviewSafetyService service = new ReviewSafetyService();

    @Test
    void flagsEmptyTextAsUnsafe() {
        ReviewSafetyReport report = service.check(" ", List.of());

        assertThat(report.safeToUseAsDraft()).isFalse();
        assertThat(report.riskScore()).isEqualTo(100);
        assertThat(report.warnings()).contains("Текст пустой.");
    }

    @Test
    void acceptsFactBasedPersonalReview() {
        ReviewSafetyReport report = service.check(
                "Покупал штукатурку, менеджер помог выбрать подходящий вариант. Доставили быстро, товар подошел для ремонта.",
                List.of("менеджер помог", "доставили быстро")
        );

        assertThat(report.safeToUseAsDraft()).isTrue();
        assertThat(report.riskScore()).isLessThan(50);
    }
}
