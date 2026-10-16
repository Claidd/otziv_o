package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientMessageReviewApprovalStateServiceTest {

    @Mock ScheduledClientMessageStateRepository repository;
    @InjectMocks ClientMessageReviewApprovalStateService service;

    @Test
    void onlyConfirmedSeventhReminderIsEligible() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(11L).orderId(42L).scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .status(ScheduledMessageStateStatus.ACTIVE).sentCount(6)
                .targetKey("order:42:cycle").build();
        when(repository.findByIdForUpdate(11L)).thenReturn(java.util.Optional.of(state));

        assertTrue(service.lockEligible(11L, 7).isEmpty());
        state.setSentCount(7);
        assertEquals(42L, service.lockEligible(11L, 7).orElseThrow().orderId());
        state.setDeliveryStatus("UNKNOWN");
        assertTrue(service.lockEligible(11L, 7).isEmpty());
    }

    @Test
    void completingApprovalStopsFutureReminders() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode("old-error")
                .lastErrorMessage("old error")
                .build();

        state.setId(11L);
        when(repository.findByIdForUpdate(11L)).thenReturn(java.util.Optional.of(state));
        service.complete(11L);

        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        assertNull(state.getLastErrorCode());
        verify(repository).save(state);
    }
}
