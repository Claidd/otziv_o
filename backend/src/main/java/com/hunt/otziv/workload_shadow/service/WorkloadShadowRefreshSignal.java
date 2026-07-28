package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.p_products.status.OrderStatusChangedEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WorkloadShadowRefreshSignal {

    private final AtomicBoolean dirty = new AtomicBoolean(true);

    public void markDirty() {
        dirty.set(true);
    }

    public boolean consume() {
        return dirty.getAndSet(false);
    }

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent ignored) {
        markDirty();
    }
}
