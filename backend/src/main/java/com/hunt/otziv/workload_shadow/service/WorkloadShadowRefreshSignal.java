package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import com.hunt.otziv.p_products.services.OrderCreatedEvent;
import com.hunt.otziv.review_recovery.event.ReviewRecoveryReleasedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WorkloadShadowRefreshSignal {

    private long dirtyRevision = 1L;
    private long refreshedRevision;
    private boolean refreshing;

    public synchronized void markDirty() {
        dirtyRevision++;
    }

    public synchronized boolean isDirty() {
        return refreshedRevision < dirtyRevision;
    }

    public synchronized boolean isProjectionStale() {
        return refreshing || isDirty();
    }

    public synchronized RefreshToken beginRefresh() {
        refreshing = true;
        return new RefreshToken(dirtyRevision);
    }

    public synchronized void completeRefresh(RefreshToken token) {
        if (token != null) {
            refreshedRevision = Math.max(refreshedRevision, token.revision());
        }
        refreshing = false;
    }

    public synchronized void failRefresh() {
        refreshing = false;
        dirtyRevision++;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStatusChanged(OrderStatusChangedEvent ignored) {
        markDirty();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCreated(OrderCreatedEvent ignored) {
        markDirty();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReviewRecoveryReleased(ReviewRecoveryReleasedEvent ignored) {
        markDirty();
    }

    public record RefreshToken(long revision) {
    }
}
