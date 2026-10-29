package com.hunt.otziv.client_messages.api;

import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

/** Scheduled-message owner contract for an authorized manager repair; see REMINDER_RESTART_20261024.md. */
public interface PeriodicReminderRestart {
    record DeliveryReference(Long stateId, String token, String targetKey, String operationId,
                             String clientId, String groupId) { }
    record ReminderRestartCheck(DeliveryReference delivery, String envelope,
            OrderDeliveryControlContext.Snapshot order, LocalDateTime preparedAt, LocalDateTime checkedAt) { }
    record PendingReminderRestart(OrderDeliveryControlContext.Snapshot order, LocalDateTime nextAt, boolean deferred) {
        public PendingReminderRestart(OrderDeliveryControlContext.Snapshot order, LocalDateTime nextAt) {
            this(order, nextAt, false);
        }
    }
    record DeferredReminderCheck(ReminderRestartCheck source, String channel) { }
    record RecoveryCandidate(long stateId, Long managerId) { }
    record ReservedReminderCheck(long stateId, String targetKey, String scenario,
            OrderDeliveryControlContext.Snapshot order, String clientId, String groupId,
            long dispatchGeneration, int sentCount, LocalDateTime lastSuccessAt, LocalDateTime checkedAt) { }

    /** Read-only inspection of the authorized manager's current cycle; provider lookup outside a write transaction. */
    Optional<ReminderRestartCheck> checkStaleReminderRestart(long stateId, Long managerId);
    /** Plans a new future periodic occurrence; does not declare the old delivery sent or unsent. */
    Optional<DeferredReminderCheck> checkDeferredReminderRestart(long stateId, Long managerId);
    List<RecoveryCandidate> deferredReminderRecoveryCandidates(long afterStateId, int limit);
    /** Full configured payment-reminder interval (at least two days), then the next working window. */
    LocalDateTime futurePaymentReminderAt(LocalDateTime from);
    /** Durable committed recovery revision, allowing card synchronization to resume after a process restart. */
    long reminderRecoveryGeneration();
    /** Caller holds the current order lock and has excluded pending manual contacts. No transport call. */
    LocalDateTime restartDeferredReminder(DeferredReminderCheck check, LocalDateTime lastContact, String actor);
    Optional<PendingReminderRestart> pendingReminderRestart(long stateId, Long managerId);
    /** Inspect a periodic task postponed before preparation because a manual contact reserved its cycle. */
    Optional<ReservedReminderCheck> checkManualReservationReminder(long stateId, Long managerId);
    /** Caller atomically audits/retires the verified cancelled manual commands; no dispatch or new operation. */
    LocalDateTime resumeAfterCancelledManualContact(ReservedReminderCheck check, LocalDateTime lastContact);
    /** Requires caller write transaction and card/order locks; revalidates the frozen source and never sends. */
    LocalDateTime restartStaleReminder(ReminderRestartCheck check, LocalDateTime lastContact, String actor);
}
