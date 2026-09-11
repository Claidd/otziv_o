package com.hunt.otziv.performers.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository.Intent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PerformerNotificationDispatcherTest {
    @Test
    void responseLossBecomesUnknownWithoutSecondTransportAttempt() {
        var notifications = mock(PerformerNotificationService.class);
        var telegram = mock(PerformerTelegramNotificationService.class);
        var intent = new Intent(1L, 2L, 3L, "OFFER", 0, "PROCESSING", "token", 1, null, null, LocalDateTime.now());
        var message = new PerformerTelegramNotificationService.Message(42L, "test", List.of());
        when(notifications.claim()).thenReturn(Optional.of(intent), Optional.empty());
        when(notifications.prepare(intent)).thenReturn(Optional.of(message));
        when(telegram.sendOnce(message)).thenThrow(new IllegalStateException("lost response"));
        new PerformerNotificationDispatcher(notifications, telegram, mock(PerformerNotificationMetrics.class)).tick();
        verify(telegram, times(1)).sendOnce(message);
        verify(notifications).complete(intent, null);
    }

    @Test
    void dispatcherRefusesAnExistingBusinessTransaction() {
        var notifications = mock(PerformerNotificationService.class);
        var telegram = mock(PerformerTelegramNotificationService.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> new PerformerNotificationDispatcher(notifications, telegram, mock(PerformerNotificationMetrics.class)).tick())
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(notifications, telegram);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
