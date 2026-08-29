package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository.Delivery;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository.NotificationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.payments.service.ManualPaymentRecipientTelegramNotificationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonInvoicePaymentNotificationDeliveryServiceTest {

    @Mock
    private CommonInvoicePaymentNotificationOutboxRepository repository;

    @Mock
    private CommonInvoicePaymentNotificationClaimService claimService;

    @Mock
    private CommonBillingService commonBillingService;

    @Mock
    private ManualPaymentRecipientTelegramNotificationService recipientNotificationService;

    @InjectMocks
    private CommonInvoicePaymentNotificationDeliveryService service;

    @Test
    void deliversRecipientAndFinalizesTheClaimOnce() {
        Delivery delivery = recipientDelivery();
        when(repository.findDueDeliveryIds(50)).thenReturn(List.of(91L));
        when(claimService.tryClaim(91L)).thenReturn(Optional.of(delivery));
        when(recipientNotificationService.notifyCommonInvoiceRecipient(any()))
                .thenReturn(ClientMessageSendResult.sent("Telegram"));
        when(claimService.markSent(delivery)).thenReturn(true);

        assertEquals(1, service.retryBatch());

        verify(recipientNotificationService).notifyCommonInvoiceRecipient(any());
        verify(claimService).markSent(delivery);
        verify(claimService, never()).markFailed(any(), any());
    }

    @Test
    void alreadyDeliveredClientNotificationIsSkippedWithoutDuplicateMessage() {
        Delivery delivery = clientDelivery();
        when(repository.findDueDeliveryIds(50)).thenReturn(List.of(92L));
        when(claimService.tryClaim(92L)).thenReturn(Optional.of(delivery));
        when(commonBillingService.deliverPaymentSuccessNotificationFromOutbox(146L))
                .thenReturn(new CommonBillingService.ClientPaymentNotificationAttempt(
                        false,
                        true,
                        "",
                        "already_notified"
                ));
        when(claimService.markSkipped(delivery, "already_notified")).thenReturn(true);

        assertEquals(0, service.retryBatch());

        verify(claimService).markSkipped(delivery, "already_notified");
        verifyNoInteractions(recipientNotificationService);
        verify(claimService, never()).markSent(any());
    }

    @Test
    void failedRecipientDeliveryRemainsRetryable() {
        Delivery delivery = recipientDelivery();
        when(repository.findDueDeliveryIds(50)).thenReturn(List.of(91L));
        when(claimService.tryClaim(91L)).thenReturn(Optional.of(delivery));
        when(recipientNotificationService.notifyCommonInvoiceRecipient(any()))
                .thenReturn(ClientMessageSendResult.failed(
                        "recipient_telegram_not_sent",
                        "Telegram не подтвердил отправку"
                ));
        when(claimService.markFailed(eq(delivery), any())).thenReturn(true);

        assertEquals(0, service.retryBatch());

        verify(claimService).markFailed(
                delivery,
                "recipient_telegram_not_sent: Telegram не подтвердил отправку"
        );
        verify(claimService, never()).markSent(any());
    }

    @Test
    void leasedCandidateIsNotSentBySecondWorker() {
        when(repository.findDueDeliveryIds(50)).thenReturn(List.of(91L));
        when(claimService.tryClaim(91L)).thenReturn(Optional.empty());

        assertEquals(0, service.retryBatch());

        verifyNoInteractions(commonBillingService, recipientNotificationService);
    }

    private Delivery clientDelivery() {
        return new Delivery(
                92L,
                146L,
                NotificationKind.CLIENT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                "82aa2b2c-cfff-4ddb-81bd-665298e0d640"
        );
    }

    private Delivery recipientDelivery() {
        return new Delivery(
                91L,
                146L,
                NotificationKind.RECIPIENT,
                123L,
                ContractorRecipientType.SPECIALIST,
                15L,
                425_000L,
                "Пластек - общий счет",
                3,
                "vika",
                LocalDateTime.of(2026, 8, 28, 21, 1),
                1,
                "82aa2b2c-cfff-4ddb-81bd-665298e0d640"
        );
    }
}
