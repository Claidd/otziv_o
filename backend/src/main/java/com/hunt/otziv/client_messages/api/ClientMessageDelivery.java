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

    /** Receipt-only lookup. Missing evidence never authorizes another provider call. */
    ClientMessageSendResult recordedOutcome(String operationId);

    /** Fresh current company preference, without changing an already frozen destination. */
    boolean publicationProgressEnabled(Long companyId);

    static boolean isKnownUnsent(ClientMessageSendResult result) {
        return result != null && !result.sent() && result.errorCode() != null
                && java.util.Set.of("company_missing", "message_empty", "chat_platform_missing", "chat_platform_unknown",
                "whatsapp_client_missing", "whatsapp_group_missing", "telegram_group_missing", "max_group_missing",
                "gateway_not_ready", "not_ready", "whatsapp_not_ready", "gateway_busy", "operation_ledger_full",
                "draining", "unauthorized", "invalid_request", "payload_too_large", "invalid_json",
                "invalid_operation_id", "invalid_operation_envelope", "max_not_configured")
                .contains(result.errorCode().trim().toLowerCase(java.util.Locale.ROOT));
    }
}
