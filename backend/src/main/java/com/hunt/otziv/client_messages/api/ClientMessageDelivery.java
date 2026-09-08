package com.hunt.otziv.client_messages.api;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;

/** Delivery for an already identified business occurrence. */
public interface ClientMessageDelivery {
    record Target(Long companyId,String title,String urlChat,Long telegramChatId,Long maxChatId) {}
    ClientMessageSendResult deliverWithOperationId(Target target,String clientId,String groupId,String message,
            TelegramTransferCopyButton transfer,String operationId);
    ClientMessageSendResult deliverPublicationProgressWithOperationId(Target target,String clientId,String groupId,
            String message,boolean includePreferenceControls,String operationId);
}
