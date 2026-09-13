package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientChatNoResponseReviewsServiceTest {
    @Test void snapshotPreservesSourceAndFencesReassignedOrUnownedMessages() {
        var repository = mock(ClientChatUnansweredItemRepository.class);
        var provider = mock(ClientChatNoResponseAiReviewService.class);
        var service = new ClientChatNoResponseReviewsService(repository, provider);
        var manager = new Manager();
        manager.setId(10L);
        var item = new ClientChatUnansweredItem();
        item.setId(5L);
        item.setManager(manager);
        item.setStatus(ClientChatUnansweredStatus.OPEN);
        item.setLastMessageText("Спасибо");
        item.setLastClientMessageAt(LocalDateTime.of(2026, 9, 13, 12, 0));
        when(repository.findById(5L)).thenReturn(Optional.of(item));

        var snapshot = service.snapshot(5L, 10L);
        assertThat(snapshot.itemId()).isEqualTo(5L);
        assertThat(snapshot.managerId()).isEqualTo(10L);
        assertThat(snapshot.messageText()).isEqualTo(item.getLastMessageText());
        assertThat(snapshot.messageAt()).isEqualTo(item.getLastClientMessageAt());
        assertThat(snapshot.review()).isNull();
        assertThatThrownBy(() -> service.snapshot(5L, 11L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.snapshot(5L, null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(provider);
    }
}
