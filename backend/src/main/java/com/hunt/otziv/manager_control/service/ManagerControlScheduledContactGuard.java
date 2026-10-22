package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.OrderManualContactReservation;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerControlScheduledContactGuard implements OrderManualContactReservation {
    private final OrderDeliveryControlContext orders;
    private final ManagerClientMessageQueue queue;
    private final ManagerControlRepairContactCarry repairCarry;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean blocksScheduledContact(long orderId) {
        var order = orders.lock(orderId).orElse(null);
        if (order == null) return false;
        return queue.controlHistory(orderId, true).stream()
                .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, order))
                .anyMatch(row -> ManagerControlOrderDeliveryState.blocks(row.delivery())
                        || ManagerControlOrderDeliveryState.recent(row))
                || repairCarry.latest(order).isPresent();
    }
}
