package com.hunt.otziv.whatsapp.api;

/**
 * Groups to which WhatsApp refused a message of an account (settled ACK -1): the account is most likely
 * no longer a member. Senders consult it before any provider call for a new operation; a replay of an
 * already dispatched operation is never held here, so an uncertain earlier dispatch is never masked.
 */
public interface WhatsAppGroupDeliveryBlocks {

    String UNAVAILABLE_CODE = "whatsapp_group_unavailable";

    String UNAVAILABLE_MESSAGE = "WhatsApp отклонил прошлое сообщение в группу клиента: номер менеджера, "
            + "похоже, больше не состоит в группе или ссылка-приглашение сброшена. Новые сообщения в эту группу "
            + "не отправляются. Попросите клиента снова добавить номер или прислать новую ссылку и сохраните её "
            + "в карточке компании";

    /** Records the refusal of {@code operationId}; a later refusal replaces the earlier one. */
    void recordRejected(String clientId, String groupId, String operationId);

    boolean isBlocked(String clientId, String groupId);

    /** A client message from the group proves this account's membership. */
    void clear(String clientId, String groupId);

    /** The manager's repair after the chat was fixed: every account may try once more. */
    void clearGroup(String groupId);
}
