package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Dispute #22: the reply was edited after it was sent, the audit only saw the first version. */
class ManagerCommunicationExchangeEditsTest {

    @Test
    void exchangeShowsTheOriginalTextAndTheLaterEditInTimeOrder() {
        ClientChatMessageRepository messages = mock(ClientChatMessageRepository.class);
        var service = new ManagerCommunicationDailyReportSectionService(mock(ClientChatUnansweredItemRepository.class),
                messages, mock(AiProviderRouter.class), new ObjectMapper(), mock(AppSettingService.class));
        LocalDateTime asked = LocalDateTime.of(2026, 10, 15, 20, 0);
        LocalDateTime closedAt = asked.plusMinutes(5);
        ClientChatUnansweredItem item = new ClientChatUnansweredItem();
        item.setId(160L);
        item.setStatus(ClientChatUnansweredStatus.ANSWERED);
        item.setPlatform(ClientChatPlatform.WHATSAPP);
        item.setChatId("group-1");
        item.setLastClientMessageAt(asked);
        item.setClosedAt(closedAt);
        ClientChatMessage question = message(1L, ClientChatSenderRole.CLIENT, "Клиент", asked,
                "2 отзыва за 3 дня!!! Каждый раз такая проблема(");
        ClientChatMessage reply = message(2L, ClientChatSenderRole.STAFF, "Анжелика", asked.plusMinutes(2),
                "Даты задаются рандомно самой системой)");
        reply.setEditedText("Даты задаются рандомно самой системой) Следующая публикация будет 22.10");
        reply.setEditedAt(asked.plusMinutes(4));
        reply.setEditCount(1);
        ClientChatMessage later = message(3L, ClientChatSenderRole.CLIENT, "Клиент", asked.plusMinutes(3), "Спасибо");
        item.setLastClientMessage(question);
        when(messages.findByPlatformAndChatIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(
                ClientChatPlatform.WHATSAPP, "group-1", asked.minusHours(12), closedAt.plusMinutes(30)))
                .thenReturn(List.of(question, reply, later));

        var exchange = service.exchange(item);

        assertThat(exchange).extracting(ManagerCommunicationDailyReportSectionService.ExchangeMessage::at)
                .containsExactly(asked, asked.plusMinutes(2), asked.plusMinutes(3), asked.plusMinutes(4));
        assertThat(exchange.get(1).text()).isEqualTo("Даты задаются рандомно самой системой) (исходный текст, позже изменён)");
        assertThat(exchange.get(3).describe()).startsWith("[2026-10-15 20:04 сотрудник Анжелика] ИЗМЕНИЛ сообщение от 2026-10-15 20:02")
                .contains("Следующая публикация будет 22.10");
    }

    private static ClientChatMessage message(Long id, ClientChatSenderRole role, String sender, LocalDateTime at, String text) {
        ClientChatMessage message = new ClientChatMessage();
        message.setId(id);
        message.setPlatform(ClientChatPlatform.WHATSAPP);
        message.setChatId("group-1");
        message.setSenderRole(role);
        message.setDirection(role == ClientChatSenderRole.STAFF ? ClientChatDirection.OUTGOING : ClientChatDirection.INCOMING);
        message.setSenderName(sender);
        message.setMessageAt(at);
        message.setMessageText(text);
        return message;
    }
}
