package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Retires old control commands only after a provably newer order cycle. No send or card action. */
@Service
@RequiredArgsConstructor
public class ManagerControlObsoleteContactSettlement {
    private final ManagerClientMessageQueue queue;
    private final OrderDeliveryControlContext orders;
    private final ClientMessageDelivery delivery;
    private final ManagerControlTransactionRunner transactions;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean retire(ManagerClientMessageQueue.Claim claim, ManagerClientMessageQueue.Command command) {
        if (claim.mayDispatch() || !java.util.List.of("UNKNOWN", "SENDING").contains(claim.previousState())
                || !"CONTROL".equals(command.kind())) return false;
        var frozen = queue.decode(command.preparedJson(), ManagerControlClientSendWorkflow.PreparedClientMessage.class);
        if (frozen.orderId() == null || !Objects.equals(frozen.deliveryToken(), command.operationId())
                || !Objects.equals(frozen.concreteItemId(), command.cardId())) return false;
        var created = queue.createdAt(command.operationId());
        if (created == null || created.isAfter(LocalDateTime.now().minusDays(2))) return false;
        var observed = orders.read(frozen.orderId()).orElse(null);
        if (observed == null || observed.cycleAt() == null || !observed.cycleAt().isAfter(created)) return false;
        // The durable provider fence must be settled UNKNOWN, never a running intent or a receipt.
        if (!delivery.hasSettledUncertainOperation(command.operationId())) return false;
        return transactions.contactReservation(() -> {
            var current = orders.lock(frozen.orderId()).orElse(null);
            if (!Objects.equals(observed, current)) return false;
            return queue.retireObsoleteUnknown(claim, current);
        });
    }
}
