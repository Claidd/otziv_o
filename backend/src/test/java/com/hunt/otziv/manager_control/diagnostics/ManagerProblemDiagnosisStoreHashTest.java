package com.hunt.otziv.manager_control.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Audit 2026-11-04: an unanswered-chat card must not queue a new evidence version every refresh. */
class ManagerProblemDiagnosisStoreHashTest {

    private static String evidenceHash(String json) {
        return ManagerProblemDiagnosisStore.hash(ManagerProblemDiagnosisStore.withoutElapsedWaiting(json));
    }

    @Test
    void onlyTheElapsedWaitingCounterIsIgnored() {
        String first = "[{\"field\":\"reason\",\"value\":\"Клиент написал 15 мин. без ответа. Последнее сообщение: Здравствуйте\"}]";
        String hourLater = "[{\"field\":\"reason\",\"value\":\"Клиент написал 1 ч. 15 мин. без ответа. Последнее сообщение: Здравствуйте\"}]";
        String daysLater = "[{\"field\":\"reason\",\"value\":\"Клиент написал 2 дн. 3 ч. без ответа. Последнее сообщение: Здравствуйте\"}]";
        String newMessage = "[{\"field\":\"reason\",\"value\":\"Клиент написал 16 мин. без ответа. Последнее сообщение: Где счёт?\"}]";
        String answered = "[{\"field\":\"reason\",\"value\":\"Клиент написал 15 мин. без ответа. Последнее сообщение: Здравствуйте\"},"
                + "{\"field\":\"status\",\"value\":\"ANSWERED\"}]";

        assertThat(evidenceHash(hourLater)).isEqualTo(evidenceHash(first));
        assertThat(evidenceHash(daysLater)).isEqualTo(evidenceHash(first));
        assertThat(evidenceHash(newMessage)).isNotEqualTo(evidenceHash(first));
        assertThat(evidenceHash(answered)).isNotEqualTo(evidenceHash(first));
    }
}
