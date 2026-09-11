package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class ManagerClientMessageWorkerTest {
    private final ManagerClientMessageQueue queue = mock(ManagerClientMessageQueue.class);
    private final ManagerControlClientSendWorkflow control = mock(ManagerControlClientSendWorkflow.class);
    private final ManagerControlClientReplyWorkflow reply = mock(ManagerControlClientReplyWorkflow.class);
    private final ClientMessageDelivery delivery = mock(ClientMessageDelivery.class);
    private final DeferredUserAuthority actors = mock(DeferredUserAuthority.class);
    private final OutboundMessagePolicy policy = mock(OutboundMessagePolicy.class);
    private final Authentication auth = UsernamePasswordAuthenticationToken.authenticated("fixture", null, List.of());
    private final ManagerClientMessageQueue.Command command = new ManagerClientMessageQueue.Command("op", 7L, "CONTROL", "snapshot",
            new DeferredUserAuthority.Actor(3L, "fixture", 1, Set.of("ROLE_MANAGER")));
    private final ManagerClientMessageWorker worker = new ManagerClientMessageWorker(queue, control, reply, delivery, actors, policy,
            new ManagerControlTransactionRunner());

    @BeforeEach void setup() {
        when(queue.snapshot("op")).thenReturn(command);
        when(policy.clientMessagesEnabled()).thenReturn(true);
        when(actors.revalidate(command.actor())).thenReturn(auth);
    }

    private ManagerClientMessageQueue.Claim claim(String previous, int attempts) {
        var claim = new ManagerClientMessageQueue.Claim("op", 7L, "CONTROL", previous, attempts, "lease");
        when(queue.claim()).thenReturn(Optional.of(claim), Optional.empty());
        return claim;
    }

    @Test void successfulDispatchRechecksAuthorityAndRestoresSchedulerSecurityContext() {
        var claim = claim("QUEUED", 0);
        var result = ClientMessageSendResult.sent("WhatsApp", "provider-id");
        when(control.dispatchQueued(command)).thenReturn(result);
        var original = SecurityContextHolder.getContext();
        doAnswer(call -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(auth);
            return null;
        }).when(control).completeQueued(claim, command, result, auth, "SENT", null, 0);
        worker.drain();
        verify(actors, times(2)).revalidate(command.actor());
        verify(control).validateQueued(command, auth);
        verify(control).completeQueued(claim, command, result, auth, "SENT", null, 0);
        assertThat(SecurityContextHolder.getContext()).isSameAs(original);
    }

    @Test void abandonedLeaseOnlyReadsReceiptAndDoesNotPostAgain() {
        var claim = claim("SENDING", 1);
        var proof = ClientMessageSendResult.sent("WhatsApp", "provider-id");
        when(delivery.recordedOutcome("op")).thenReturn(proof);
        worker.drain();
        verify(control, never()).dispatchQueued(any());
        verify(control).completeQueued(claim, command, proof, auth, "SENT", null, 0);
    }

    @Test void lostResponseRemainsUnknownWithoutAnAutomaticResend() {
        var claim = claim("QUEUED", 0);
        when(control.dispatchQueued(command)).thenThrow(new IllegalStateException("timeout"));
        worker.drain();
        verify(control).completeQueued(claim, command, null, null, "UNKNOWN", "sender_exception", 300);
        var receiptClaim = claim("UNKNOWN", 1);
        when(delivery.recordedOutcome("op")).thenReturn(ClientMessageSendResult.failed("operation_unknown", "unavailable"));
        worker.drain();
        verify(control, times(1)).dispatchQueued(command);
        verify(control).completeQueued(receiptClaim, command, null, null, "UNKNOWN", "operation_unknown", 300);
    }

    @Test void pausedDeliveryDoesNotCallTheProviderOrResolveTheBusinessCard() {
        var claim = claim("QUEUED", 0);
        when(policy.clientMessagesEnabled()).thenReturn(false);
        worker.drain();
        verify(queue).finish(claim, "RETRYABLE", "live_disabled", 300);
        verifyNoInteractions(control, reply, delivery);
    }

    @Test void revokedActorOrChangedSourceCannotDispatch() {
        var claim = claim("QUEUED", 0);
        when(actors.revalidate(command.actor())).thenThrow(new AccessDeniedException("revoked"));
        worker.drain();
        verify(control).completeQueued(claim, command, null, null, "FAILED", "context_changed", 0);
        verify(control, never()).dispatchQueued(any());
        verifyNoInteractions(reply, delivery);
    }

    @Test void changedSourceCannotDispatchEvenWhenTheActorIsStillActive() {
        var claim = claim("QUEUED", 0);
        doThrow(new IllegalStateException("source changed")).when(control).validateQueued(command, auth);
        worker.drain();
        verify(control).completeQueued(claim, command, null, null, "FAILED", "context_changed", 0);
        verify(control, never()).dispatchQueued(any());
    }

    @Test void knownAdmissionRejectionRetriesWithBoundAndOnlyThenReleasesTheCard() {
        var result = ClientMessageSendResult.failed("gateway_not_ready", "not ready");
        when(control.dispatchQueued(command)).thenReturn(result);
        var first = claim("QUEUED", 0); worker.drain();
        verify(queue).finish(first, "RETRYABLE", "gateway_not_ready", 30);
        verify(control, never()).completeQueued(any(), any(), any(), any(), any(), any(), anyInt());
        var last = claim("RETRYABLE", 4); worker.drain();
        verify(control).completeQueued(last, command, result, auth, "FAILED", "gateway_not_ready", 0);
    }

    @Test void deliveryConfirmedAfterPermissionsChangeDoesNotCloseOrResendTheCard() {
        var claim = claim("QUEUED", 0);
        when(control.dispatchQueued(command)).thenReturn(ClientMessageSendResult.sent("WhatsApp", "provider-id"));
        when(actors.revalidate(command.actor())).thenReturn(auth).thenThrow(new AccessDeniedException("revoked"));
        worker.drain();
        verify(control).completeQueued(eq(claim), eq(command), any(), isNull(), eq("SENT"), eq("finalization_required"), eq(0));
        verify(control, never()).completeQueued(any(), any(), any(), eq(auth), any(), any(), anyInt());
        verify(control, times(1)).dispatchQueued(command);
    }
}
