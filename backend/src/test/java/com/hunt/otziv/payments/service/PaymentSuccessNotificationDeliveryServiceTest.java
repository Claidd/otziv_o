package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PaymentSuccessNotificationDeliveryServiceTest {

    private static final long LINK_ID = 207L;

    private final PaymentLinkRepository paymentLinkRepository = mock(PaymentLinkRepository.class);
    private final PaymentSuccessClientNotifier notifier = mock(PaymentSuccessClientNotifier.class);
    private final PaymentSuccessNotificationRetryClaimService claimService =
            mock(PaymentSuccessNotificationRetryClaimService.class);
    private final PaymentSuccessNotificationDeliveryService service =
            new PaymentSuccessNotificationDeliveryService(
                    paymentLinkRepository,
                    notifier,
                    claimService,
                    new PaymentLinkTransactionExecutor()
            );

    @Test
    void directAndRetryDeliveryBothRequireTheSharedDurableClaim() {
        when(claimService.tryClaim(LINK_ID)).thenReturn(Optional.empty());

        assertThat(service.tryDeliver(LINK_ID)).isFalse();

        verify(claimService).tryClaim(LINK_ID);
        verifyNoInteractions(paymentLinkRepository, notifier);
    }

    @Test
    void sendsOnlyAfterClaimAndFinalizesWithTheSameFence() {
        PaymentLink link = link();
        PaymentSuccessNotificationRetryClaimService.Claim claim = claim();
        when(claimService.tryClaim(LINK_ID)).thenReturn(Optional.of(claim));
        when(paymentLinkRepository.findByIdWithOrder(LINK_ID)).thenReturn(Optional.of(link));
        when(notifier.notifySuccess(link)).thenReturn(ClientMessageSendResult.sent("Telegram"));
        when(claimService.markSucceeded(claim)).thenReturn(true);

        assertThat(service.tryDeliver(LINK_ID)).isTrue();

        InOrder order = inOrder(claimService, paymentLinkRepository, notifier);
        order.verify(claimService).tryClaim(LINK_ID);
        order.verify(paymentLinkRepository).findByIdWithOrder(LINK_ID);
        order.verify(notifier).notifySuccess(link);
        order.verify(claimService).markSucceeded(claim);
        assertThat(link.getPaymentSuccessNotifiedAt()).isNotNull();
        assertThat(link.isPaymentSuccessNotificationRetryEligible()).isFalse();
    }

    @Test
    void failedProviderCallLeavesTheWorkRetryEligible() {
        PaymentLink link = link();
        link.setPaymentSuccessNotificationRetryEligible(true);
        PaymentSuccessNotificationRetryClaimService.Claim claim = claim();
        when(claimService.tryClaim(LINK_ID)).thenReturn(Optional.of(claim));
        when(paymentLinkRepository.findByIdWithOrder(LINK_ID)).thenReturn(Optional.of(link));
        when(notifier.notifySuccess(link))
                .thenReturn(ClientMessageSendResult.failed("whatsapp_error", "not ready"));
        when(claimService.markFailed(claim, "whatsapp_error: not ready")).thenReturn(true);

        assertThat(service.tryDeliver(LINK_ID)).isFalse();

        verify(claimService).markFailed(claim, "whatsapp_error: not ready");
        assertThat(link.isPaymentSuccessNotificationRetryEligible()).isTrue();
        assertThat(link.getPaymentSuccessNotificationError()).isEqualTo("whatsapp_error: not ready");
    }

    @Test
    void successfulProviderCallCannotBeFinalizedByAStaleLeaseOwner() {
        PaymentLink link = link();
        PaymentSuccessNotificationRetryClaimService.Claim claim = claim();
        when(claimService.tryClaim(LINK_ID)).thenReturn(Optional.of(claim));
        when(paymentLinkRepository.findByIdWithOrder(LINK_ID)).thenReturn(Optional.of(link));
        when(notifier.notifySuccess(link)).thenReturn(ClientMessageSendResult.sent("MAX"));
        when(claimService.markSucceeded(claim)).thenReturn(false);

        assertThat(service.tryDeliver(LINK_ID)).isFalse();

        verify(claimService).markSucceeded(claim);
        verify(claimService, never()).markFailed(claim, "notification_delivery_failed");
        assertThat(link.getPaymentSuccessNotifiedAt()).isNull();
    }

    @Test
    void activePaymentTransactionDefersClaimAndProviderIoUntilAfterCommit() {
        when(claimService.tryClaim(LINK_ID)).thenReturn(Optional.empty());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deliverAfterCommit(LINK_ID);

            verifyNoInteractions(claimService, paymentLinkRepository, notifier);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCommit();

            verify(claimService).tryClaim(LINK_ID);
            verifyNoInteractions(paymentLinkRepository, notifier);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private PaymentLink link() {
        PaymentLink link = new PaymentLink();
        link.setId(LINK_ID);
        link.setPaymentSuccessNotificationRetryEligible(true);
        return link;
    }

    private PaymentSuccessNotificationRetryClaimService.Claim claim() {
        return new PaymentSuccessNotificationRetryClaimService.Claim(
                LINK_ID,
                UUID.randomUUID().toString()
        );
    }
}
