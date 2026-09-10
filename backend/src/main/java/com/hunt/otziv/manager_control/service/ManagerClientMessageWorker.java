package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Provider calls run outside database transactions; abandoned claims only query receipts. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerClientMessageWorker {
    private final ManagerClientMessageQueue queue;
    private final ManagerControlClientSendWorkflow control;
    private final ManagerControlClientReplyWorkflow reply;
    private final ClientMessageDelivery delivery;
    private final DeferredUserAuthority actors;
    private final OutboundMessagePolicy policy;
    private final ManagerControlTransactionRunner transactions;

    @Scheduled(fixedDelay = 5000, scheduler = "managerClientMessageScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void drain() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Manager delivery must not join a business transaction");
        for (int i = 0; i < 5; i++) {
            var claim = queue.claim();
            if (claim.isEmpty()) return;
            process(claim.get());
        }
    }

    private void process(ManagerClientMessageQueue.Claim claim) {
        ManagerClientMessageQueue.Command command;
        try { command = queue.snapshot(claim.operationId()); }
        catch (RuntimeException unreadable) { finish(claim, "UNKNOWN", "snapshot_unreadable", 300); return; }
        ClientMessageSendResult result;
        Authentication authentication = null;
        if (claim.mayDispatch()) {
            if (!policy.clientMessagesEnabled()) { finish(claim, "RETRYABLE", "live_disabled", 300); return; }
            try {
                authentication = actors.revalidate(command.actor());
                if ("CONTROL".equals(command.kind())) control.validateQueued(command, authentication);
                else reply.validateQueued(command, authentication);
            } catch (RuntimeException stale) {
                // There was no provider call. Keep the card fence for explicit source/permission review.
                try {
                    if ("CONTROL".equals(command.kind())) control.completeQueued(claim, command, null, null, "FAILED", "context_changed", 0);
                    else reply.completeQueued(claim, command, null, null, "FAILED", "context_changed", 0);
                } catch (RuntimeException unavailable) { finish(claim, "FAILED", "context_changed", 0); }
                return;
            }
            try {
                result = "CONTROL".equals(command.kind()) ? control.dispatchQueued(command) : reply.dispatchQueued(command);
            } catch (RuntimeException uncertain) { holdUnknown(claim, command, "sender_exception"); return; }
        } else {
            try { result = delivery.recordedOutcome(claim.operationId()); }
            catch (RuntimeException unavailable) { holdUnknown(claim, command, "receipt_unavailable"); return; }
        }
        boolean confirmed = result != null && result.sent() && result.messageId() != null
                && !result.messageId().isBlank() && result.messageId().length() <= 512;
        boolean knownUnsent = ClientMessageDelivery.isKnownUnsent(result);
        int attempts = claim.attempts() + (claim.mayDispatch() ? 1 : 0);
        String state = confirmed ? "SENT" : knownUnsent ? (attempts >= 5 ? "FAILED" : "RETRYABLE") : "UNKNOWN";
        String code = confirmed ? null : result == null ? "unconfirmed_result" : result.errorCode();
        int delay = "UNKNOWN".equals(state) ? 300 : "RETRYABLE".equals(state) ? Math.min(300, 15 << Math.min(attempts, 4)) : 0;
        if ("UNKNOWN".equals(state)) { holdUnknown(claim, command, code); return; }
        if ("RETRYABLE".equals(state) && claim.mayDispatch()) { finish(claim, state, code, delay); return; }
        var previousContext = SecurityContextHolder.getContext();
        try {
            // Recheck revocation after slow I/O as well; do not borrow scheduler/system authority.
            authentication = actors.revalidate(command.actor());
            var scoped = SecurityContextHolder.createEmptyContext();
            scoped.setAuthentication(authentication);
            SecurityContextHolder.setContext(scoped);
            if ("CONTROL".equals(command.kind())) control.completeQueued(claim, command, result, authentication, state, code, delay);
            else reply.completeQueued(claim, command, result, authentication, state, code, delay);
        } catch (RuntimeException finalizationFailed) {
            // Preserve proven delivery, but never re-arm or close a changed business source.
            String heldState = "RETRYABLE".equals(state) ? "FAILED" : state;
            try {
                if ("CONTROL".equals(command.kind())) control.completeQueued(claim, command, result, null, heldState, "finalization_required", 0);
                else reply.completeQueued(claim, command, result, null, heldState, "finalization_required", 0);
            } catch (RuntimeException unavailable) { finish(claim, heldState, "finalization_required", 0); }
            log.warn("Manager command needs business reconciliation: operationId={}", claim.operationId());
        } finally { SecurityContextHolder.setContext(previousContext); }
    }

    private void finish(ManagerClientMessageQueue.Claim claim, String state, String code, int delay) {
        transactions.required(() -> { queue.finish(claim, state, code, delay); return null; });
    }

    private void holdUnknown(ManagerClientMessageQueue.Claim claim, ManagerClientMessageQueue.Command command, String code) {
        try {
            if ("CONTROL".equals(command.kind())) control.completeQueued(claim, command, null, null, "UNKNOWN", code, 300);
            else reply.completeQueued(claim, command, null, null, "UNKNOWN", code, 300);
        } catch (RuntimeException unavailable) { finish(claim, "UNKNOWN", code, 300); }
    }
}
