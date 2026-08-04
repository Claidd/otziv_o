package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentInvoiceRetrySchedulerTest {

    @Mock
    private ScheduledClientMessageStateRepository stateRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ClientMessageSlotPlanner slotPlanner;

    @Test
    void finalManualPaymentClosesActiveAndPausedPaymentAutomation() {
        PaymentInvoiceRetryScheduler scheduler = new PaymentInvoiceRetryScheduler(
                stateRepository,
                appSettingService,
                slotPlanner
        );
        ScheduledClientMessageState activeReminder = state(
                ClientMessageScenario.PAYMENT_REMINDER,
                ScheduledMessageStateStatus.ACTIVE
        );
        ScheduledClientMessageState pausedAutoBan = state(
                ClientMessageScenario.BAD_REVIEW_AUTO_BAN,
                ScheduledMessageStateStatus.PAUSED
        );
        ScheduledClientMessageState unrelated = state(
                ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY,
                ScheduledMessageStateStatus.ACTIVE
        );
        when(stateRepository.findByOrderIdIn(List.of(25047L)))
                .thenReturn(List.of(activeReminder, pausedAutoBan, unrelated));

        int changed = scheduler.cancelPaymentAutomation(25047L, "Оплата подтверждена");

        assertEquals(2, changed);
        assertEquals(ScheduledMessageStateStatus.DONE, activeReminder.getStatus());
        assertEquals(ScheduledMessageStateStatus.DONE, pausedAutoBan.getStatus());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, unrelated.getStatus());
        assertNull(activeReminder.getNextAttemptAt());
        assertNull(pausedAutoBan.getLockedUntil());
        assertEquals("manual_card_payment_confirmed", activeReminder.getLastErrorCode());
        assertEquals("Оплата подтверждена", pausedAutoBan.getLastErrorMessage());
        verify(stateRepository).saveAll(List.of(activeReminder, pausedAutoBan));
    }

    private ScheduledClientMessageState state(
            ClientMessageScenario scenario,
            ScheduledMessageStateStatus status
    ) {
        return ScheduledClientMessageState.builder()
                .scenario(scenario)
                .orderId(25047L)
                .targetKey(scenario.name() + ":25047")
                .status(status)
                .nextAttemptAt(LocalDateTime.now().plusHours(1))
                .lockedUntil(LocalDateTime.now().plusMinutes(5))
                .consecutiveFailures(3)
                .build();
    }
}
