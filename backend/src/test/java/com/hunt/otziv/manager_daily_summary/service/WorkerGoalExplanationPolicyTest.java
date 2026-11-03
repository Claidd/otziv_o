package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerGoalExplanationPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Я отправила тексты на проверку", "Я переслала отзывы", "Я выслала документы",
            "Я направила файлы", "Я отправила сообщение специалисту со всеми текстами",
            "Я отправила сообщение специалисту, что все тексты готовы", "Я отправила клиенту сообщение об оплате"
    })
    void aReportedDeliveryOrCompletedResultStillNeedsProof(String quote) {
        assertThat(WorkerGoalExplanationPolicy.containsUnverifiedOutcome(quote)).isTrue();
        assertThat(WorkerGoalExplanationPolicy.reportedExplanation(quote, true)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Я отправила сообщение специалисту", "Я переслала ей напоминание", "Я направила ему смс"
    })
    void aPrivateContactMessageIsAnAttributedExplanation(String quote) {
        assertThat(WorkerGoalExplanationPolicy.containsUnverifiedOutcome(quote)).isFalse();
        assertThat(WorkerGoalExplanationPolicy.reportedExplanation(quote, false)).isTrue();
    }
}
