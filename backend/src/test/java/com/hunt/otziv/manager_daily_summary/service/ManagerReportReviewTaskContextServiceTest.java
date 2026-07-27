package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import com.hunt.otziv.client_chat_control.model.ClientChatResolutionType;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagerReportReviewTaskContextServiceTest {

    private ManagerDailyControlConcreteItemRepository concreteRepository;
    private ClientChatUnansweredItemRepository unansweredRepository;
    private ManagerReportReviewTaskContextService service;

    @BeforeEach
    void setUp() {
        concreteRepository = mock(ManagerDailyControlConcreteItemRepository.class);
        unansweredRepository = mock(ClientChatUnansweredItemRepository.class);
        service = new ManagerReportReviewTaskContextService(concreteRepository, unansweredRepository);
    }

    @Test
    void includesClientMessageManagerReplyAndQualityInFreshContext() {
        ManagerDailyControlConcreteItem source = source(3545L, ManagerDailyControlItemStatus.RESOLVED);
        ClientChatUnansweredItem chat = answered(
                ClientChatReplyQuality.GOOD,
                false,
                "Добрый день! Пока приостановим",
                "Здравствуйте, поняла вас, хорошо"
        );
        when(concreteRepository.findAllById(java.util.Set.of(3545L))).thenReturn(List.of(source));
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));
        when(unansweredRepository.findById(1387L)).thenReturn(Optional.of(chat));

        String context = service.refresh("Карточка Каприз\nsourceTaskId=3545");

        assertThat(context)
                .contains("currentStatus=RESOLVED")
                .contains("clientMessage=Добрый день! Пока приостановим")
                .contains("managerReply=Здравствуйте, поняла вас, хорошо")
                .contains("replyQuality=GOOD");
        assertThat(service.resolvedSatisfactorily(3545L)).isTrue();
    }

    @Test
    void doesNotTreatPartialReplyAsSatisfactoryCompletion() {
        ManagerDailyControlConcreteItem source = source(3545L, ManagerDailyControlItemStatus.RESOLVED);
        ClientChatUnansweredItem chat = answered(
                ClientChatReplyQuality.PARTIAL,
                true,
                "Когда будет готово?",
                "Хорошо"
        );
        when(concreteRepository.findById(3545L)).thenReturn(Optional.of(source));
        when(unansweredRepository.findById(1387L)).thenReturn(Optional.of(chat));

        assertThat(service.resolvedSatisfactorily(3545L)).isFalse();
    }

    private ManagerDailyControlConcreteItem source(
            Long id,
            ManagerDailyControlItemStatus status
    ) {
        ManagerDailyControlConcreteItem source = new ManagerDailyControlConcreteItem();
        source.setId(id);
        source.setEntityType("CLIENT_CHAT_UNANSWERED");
        source.setEntityId(1387L);
        source.setTitle("Каприз");
        source.setStatus(status);
        return source;
    }

    private ClientChatUnansweredItem answered(
            ClientChatReplyQuality quality,
            boolean auditRequired,
            String clientMessage,
            String managerReply
    ) {
        ClientChatUnansweredItem item = new ClientChatUnansweredItem();
        item.setStatus(ClientChatUnansweredStatus.ANSWERED);
        item.setLastMessageText(clientMessage);
        item.setResolutionReplyText(managerReply);
        item.setResolutionType(ClientChatResolutionType.ANSWERED);
        item.setReplyQuality(quality);
        item.setReplyQualityReason(quality == ClientChatReplyQuality.GOOD
                ? "Ответ соответствует сообщению клиента"
                : "Ответ слишком общий");
        item.setAuditRequired(auditRequired);
        return item;
    }
}
