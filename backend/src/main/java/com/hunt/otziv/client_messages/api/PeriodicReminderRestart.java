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
    record ReservedReminderCheck(long stateId, String targetKey, String scenario,
            OrderDeliveryControlContext.Snapshot order, String clientId, String groupId,
            long dispatchGeneration, int sentCount, LocalDateTime lastSuccessAt, LocalDateTime checkedAt) { }

    /** Read-only inspection of the authorized manager's current cycle; provider lookup outside a write transaction. */
    Optional<ReminderRestartCheck> checkStaleReminderRestart(long stateId, Long managerId);
    Optional<PendingReminderRestart> pendingReminderRestart(long stateId, Long managerId);
    /** Inspect a periodic task postponed before preparation because a manual contact reserved its cycle. */
    Optional<ReservedReminderCheck> checkManualReservationReminder(long stateId, Long managerId);
    /** Caller atomically audits/retires the verified cancelled manual commands; no dispatch or new operation. */
    LocalDateTime resumeAfterCancelledManualContact(ReservedReminderCheck check, LocalDateTime lastContact);
    /** Requires caller write transaction and card/order locks; revalidates the frozen source and never sends. */
    LocalDateTime restartStaleReminder(ReminderRestartCheck check, LocalDateTime lastContact, String actor);
}
