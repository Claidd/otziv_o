package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.CurrentOrderDeliveryUncertainty;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentOrderDeliveryUncertaintyService implements CurrentOrderDeliveryUncertainty {
    private final ScheduledClientMessageStateRepository states;
    public boolean hasUncertainCurrentDelivery(long orderId, String orderTarget, String waitingTarget) {
        return states.findByOrderIdIn(List.of(orderId)).stream()
                .filter(s -> s.getStatus() == ScheduledMessageStateStatus.ACTIVE)
                .filter(s -> Objects.equals(orderTarget, s.getTargetKey())
                        || (waitingTarget != null && Objects.equals(waitingTarget, s.getTargetKey())))
                .anyMatch(ClientMessageStateSafety::blocksAutomaticRearm);
    }
    public boolean requiresReconciliation(long stateId) {
        return states.findById(stateId).map(ClientMessageStateSafety::blocksAutomaticRearm).orElse(false);
    }
}
