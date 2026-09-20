package com.hunt.otziv.admin.service;

import com.hunt.otziv.admin.repository.BotDuplicateReportRepository;
import com.hunt.otziv.admin.repository.BotDuplicateReportRepository.Claim;
import com.hunt.otziv.t_telegrambot.api.TelegramAdminDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class BotDuplicateReportSenderTest {
    private final BotDuplicateReportRepository repository = mock(BotDuplicateReportRepository.class);
    private final TelegramAdminDocuments telegram = mock(TelegramAdminDocuments.class);
    private final BotDuplicateReportSender sender = new BotDuplicateReportSender(repository, telegram);
    private final Claim claim = new Claim("report", "token", "duplicates.txt", "Отчёт", null, 1);

    @BeforeEach
    void setUp() {
        when(telegram.canSendDocuments()).thenReturn(true);
        when(telegram.adminDocumentRecipients()).thenReturn(List.of(11L, 22L));
        when(repository.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        when(repository.savePendingRecipients(any(), anyList())).thenReturn(true);
        when(repository.renewLease(any())).thenReturn(true);
        when(repository.deleteDelivered(any())).thenReturn(true);
    }

    @Test
    void deletesTemporaryContentsOnlyAfterConfirmedDeliveryToEveryAdmin() {
        when(telegram.sendDocumentOnceMessageId(anyLong(), any(), anyString())).thenReturn(Optional.of(100));
        sender.sendPending();
        var order = inOrder(repository, telegram);
        order.verify(repository).savePendingRecipients(claim, List.of(11L, 22L));
        order.verify(telegram).sendDocumentOnceMessageId(eq(11L), aryEq("Отчёт".getBytes(StandardCharsets.UTF_8)), eq("duplicates.txt"));
        order.verify(repository).savePendingRecipients(claim, List.of(22L));
        order.verify(telegram).sendDocumentOnceMessageId(eq(22L), any(), eq("duplicates.txt"));
        order.verify(repository).savePendingRecipients(claim, List.of());
        order.verify(repository).deleteDelivered(claim);
        verify(repository, never()).retryLater(any());
    }

    @Test
    void retriesOnlyUnconfirmedRecipientsAfterPartialDelivery() {
        when(telegram.sendDocumentOnceMessageId(eq(11L), any(), anyString())).thenReturn(Optional.of(100));
        when(telegram.sendDocumentOnceMessageId(eq(22L), any(), anyString())).thenReturn(Optional.empty());
        sender.sendPending();
        verify(repository).savePendingRecipients(claim, List.of(22L));
        verify(repository).retryLater(claim);
        verify(repository, never()).deleteDelivered(any());

        var retry = new Claim(claim.id(), "new-token", claim.fileName(), claim.reportText(), List.of(22L), 2);
        when(repository.claimNext()).thenReturn(Optional.of(retry), Optional.empty());
        when(telegram.sendDocumentOnceMessageId(eq(22L), any(), anyString())).thenReturn(Optional.of(101));
        sender.sendPending();
        verify(telegram, times(1)).sendDocumentOnceMessageId(eq(11L), any(), anyString());
        verify(telegram, times(2)).sendDocumentOnceMessageId(eq(22L), any(), anyString());
        verify(repository).deleteDelivered(retry);
    }

    @Test
    void preservesReportWhenNoAdminHasTelegramConfigured() {
        when(telegram.adminDocumentRecipients()).thenReturn(List.of());
        sender.sendPending();
        verify(repository).retryLater(claim);
        verify(repository, never()).deleteDelivered(any());
        verify(repository, never()).savePendingRecipients(any(), anyList());
        verify(telegram, never()).sendDocumentOnceMessageId(anyLong(), any(), anyString());
    }

    @Test
    void cleansUpAlreadyDeliveredReportWithoutSendingAgainAfterRestart() {
        var delivered = new Claim(claim.id(), "new-token", claim.fileName(), claim.reportText(), List.of(), 2);
        when(repository.claimNext()).thenReturn(Optional.of(delivered), Optional.empty());
        sender.sendPending();
        verify(telegram, never()).sendDocumentOnceMessageId(anyLong(), any(), anyString());
        verify(repository).deleteDelivered(delivered);
    }

    @Test
    void disabledSendingDoesNotClaimOrDeleteReports() {
        when(telegram.canSendDocuments()).thenReturn(false);
        sender.sendPending();
        verifyNoInteractions(repository);
    }

    @Test
    void configuredAdminChatsAreDeduplicatedAndZeroIsIgnored() {
        when(telegram.adminDocumentRecipients()).thenReturn(List.of(11L, 11L, 0L));
        when(telegram.sendDocumentOnceMessageId(eq(11L), any(), anyString())).thenReturn(Optional.of(100));
        sender.sendPending();
        verify(repository).savePendingRecipients(claim, List.of(11L));
        verify(telegram, times(1)).sendDocumentOnceMessageId(eq(11L), any(), anyString());
        verify(repository).deleteDelivered(claim);
    }

    @Test
    void lostLeaseStopsSendingAndCleanup() {
        when(repository.renewLease(claim)).thenReturn(false);
        sender.sendPending();
        verify(telegram, never()).sendDocumentOnceMessageId(anyLong(), any(), anyString());
        verify(repository, never()).deleteDelivered(any());
    }
}
