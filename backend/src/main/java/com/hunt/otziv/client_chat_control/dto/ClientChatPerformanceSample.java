package com.hunt.otziv.client_chat_control.dto;

import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import java.time.LocalDateTime;
import lombok.Value;

/** Exact inputs to the manager score; conversation bodies stay in their owner read model. */
@Value
public class ClientChatPerformanceSample {
    Long id;
    Long managerId;
    ClientChatUnansweredStatus status;
    LocalDateTime lastClientMessageAt;
    LocalDateTime closedAt;
}
