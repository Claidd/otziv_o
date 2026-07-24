package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientChatMessageImmediateVisibilityTest {

    @Mock private ClientChatMessageRepository messageRepository;
    @Mock private ClientChatUnansweredItemRepository unansweredRepository;
    @Mock private ClientChatParticipantClassifier participantClassifier;
    @Mock private ClientChatAutoIgnoreService autoIgnoreService;
    @Mock private ClientChatCompanyResolutionService companyResolutionService;
    @Mock private AppSettingService appSettingService;
    @Mock private GamificationEventService gamificationEventService;

    private ClientChatMessageTrackerService service;

    @BeforeEach
    void setUp() {
        service = new ClientChatMessageTrackerService(
                messageRepository,
                unansweredRepository,
                participantClassifier,
                autoIgnoreService,
                companyResolutionService,
                appSettingService,
                gamificationEventService
        );
    }

    @Test
    void zeroWarningMakesOpenMessageDueImmediately() {
        Manager manager = new Manager();
        when(appSettingService.getBoolean("manager-control.unanswered-client-messages.enabled", true))
                .thenReturn(true);
        when(appSettingService.getInt("manager-control.unanswered-client-messages.warning-minutes", 0))
                .thenReturn(0);
        when(unansweredRepository.countByManagerAndStatusAndLastClientMessageAtLessThanEqual(
                any(), any(), any()
        )).thenReturn(1L);
        LocalDateTime before = LocalDateTime.now();

        assertEquals(1L, service.countDue(manager));

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(unansweredRepository).countByManagerAndStatusAndLastClientMessageAtLessThanEqual(
                eq(manager),
                eq(ClientChatUnansweredStatus.OPEN),
                cutoff.capture()
        );
        assertFalse(cutoff.getValue().isBefore(before));
        assertFalse(cutoff.getValue().isAfter(LocalDateTime.now()));
    }
}
