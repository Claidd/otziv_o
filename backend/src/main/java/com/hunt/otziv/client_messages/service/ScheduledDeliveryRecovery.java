package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import java.time.LocalDateTime;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receipt lookup has no dispatch path. A missing receipt always preserves the delivery barrier. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledDeliveryRecovery {
    private final ScheduledClientMessageStateRepository states;
    private final ClientMessageTransactionRunner transactions;
    private final ClientMessageDelivery delivery;

    public void recover(LocalDateTime now, LocalDateTime cutoff, Function<String, String> operationId,
            BiConsumer<String, ClientMessageSendResult> finalizeReceipt) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Receipt lookup must run outside a business transaction");
        }
        for (Long stateId : states.findRecoverablePreparedIds(cutoff, PageRequest.of(0, 20))) {
            try {
                RecoverySnapshot snapshot = transactions.callInNewTransaction(() -> {
                    var state = states.findByIdForUpdate(stateId).orElse(null);
                    if (state == null || state.getDeliveryEnvelope() == null || state.getDeliveryEnvelope().isBlank()
                            || !("PREPARED".equals(state.getDeliveryStatus()) || "UNKNOWN".equals(state.getDeliveryStatus()))) return null;
                    var lastChecked = state.getDeliveryRecoveryCheckedAt() != null
                            ? state.getDeliveryRecoveryCheckedAt() : state.getDeliveryPreparedAt();
                    if (lastChecked == null || !lastChecked.isBefore(cutoff)) return null;
                    state.setDeliveryStatus("UNKNOWN");
                    // Rotate even corrupt/old snapshots, preventing starvation in bounded batches.
                    state.setDeliveryRecoveryCheckedAt(now);
                    if (state.getStatus() == ScheduledMessageStateStatus.ACTIVE) {
                        if (!ClientMessageStateSafety.MANUAL_DELIVERY_HANDOFF_PENDING.equals(state.getLastErrorCode()))
                            state.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN);
                        state.setNextAttemptAt(null);
                        state.setLockedUntil(null);
                    }
                    states.save(state);
                    return new RecoverySnapshot(state.getDeliveryEnvelope(), state.getDeliveryPreparedAt());
                });
                if (snapshot == null) continue;
                ClientMessageSendResult receipt = delivery.recoverRecordedOutcomeFromChat(
                        operationId.apply(snapshot.envelope()), snapshot.preparedAt());
                if (receipt != null && receipt.sent()) {
                    finalizeReceipt.accept(snapshot.envelope(), receipt);
                }
            } catch (RuntimeException failure) {
                log.warn("Scheduled delivery recovery held for review: stateId={}, errorType={}", stateId, failure.getClass().getSimpleName());
            }
        }
    }
    private record RecoverySnapshot(String envelope, LocalDateTime preparedAt) { }
}
