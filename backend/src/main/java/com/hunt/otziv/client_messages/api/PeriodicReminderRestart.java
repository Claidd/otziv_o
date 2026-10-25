package com.hunt.otziv.client_messages.api;

import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import java.time.LocalDateTime;
import java.util.Optional;

/** Scheduled-message owner contract for an authorized manager repair; see REMINDER_RESTART_20261024.md. */
public interface PeriodicReminderRestart {
    record DeliveryReference(Long stateId, String token, String targetKey, String operationId,
                             String clientId, String groupId) { }
    record ReminderRestartCheck(DeliveryReference delivery, String envelope,
            OrderDeliveryControlContext.Snapshot order, LocalDateTime preparedAt, LocalDateTime checkedAt) { }
    record PendingReminderRestart(OrderDeliveryControlContext.Snapshot order, LocalDateTime nextAt) { }

    /** Read-only inspection of the authorized manager's current cycle; provider lookup outside a write transaction. */
    Optional<ReminderRestartCheck> checkStaleReminderRestart(long stateId, Long managerId);
    Optional<PendingReminderRestart> pendingReminderRestart(long stateId, Long managerId);
    /** Requires caller write transaction and card/order locks; revalidates the frozen source and never sends. */
    LocalDateTime restartStaleReminder(ReminderRestartCheck check, LocalDateTime lastContact, String actor);
}
