package com.hunt.otziv.gamification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class GamificationUserProgressTransactionContractTest {

    @Test
    @DisplayName("Личный прогресс допускает ленивую выдачу жетонов в той же транзакции")
    void myProgressUsesWritableTransactionForLazyLevelRewards() throws NoSuchMethodException {
        Transactional transaction = GamificationUserProgressService.class
                .getMethod("myProgress", Principal.class, int.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isFalse();
    }
}
