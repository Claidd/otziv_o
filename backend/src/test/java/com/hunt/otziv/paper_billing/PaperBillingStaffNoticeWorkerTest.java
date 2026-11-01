package com.hunt.otziv.paper_billing;

import com.hunt.otziv.client_messages.api.PaperInvoiceAutomation;
import com.hunt.otziv.common_billing.api.BankPaperInvoices;
import com.hunt.otziv.p_products.api.PaperInvoiceOrderData;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import com.hunt.otziv.u_users.api.PaymentNoticeRecipients;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperBillingStaffNoticeWorkerTest {
    final PaperBillingSettings settings=new PaperBillingSettings();
    final PaperBillingStore documents=mock(PaperBillingStore.class);
    final PaperBillingNoticeStore notices=mock(PaperBillingNoticeStore.class);
    final PaperInvoiceOrderData orders=mock(PaperInvoiceOrderData.class);
    final BankPaperInvoices common=mock(BankPaperInvoices.class);
    final PaperInvoiceAutomation automation=mock(PaperInvoiceAutomation.class);
    final PaymentNoticeRecipients recipients=mock(PaymentNoticeRecipients.class);
    final TelegramNotifications telegram=mock(TelegramNotifications.class);
    final PaperBillingBankClient bank=mock(PaperBillingBankClient.class);
    final PaperBillingStaffNoticeWorker worker=new PaperBillingStaffNoticeWorker(settings,documents,notices,orders,common,automation,recipients,telegram,bank);
    Map<String,Object> row(String state,Long chat) {
        var row=new HashMap<String,Object>();row.put("state",state);row.put("recipient_user_id",20L);row.put("manager_id",7L);row.put("chat_id",chat);row.put("body","Оплата получена");return row;
    }
    @Test void uncertainTelegramResultIsNeverSentAgain() {
        settings.setEnabled(true);var row=row("PENDING",9191L);
        when(notices.row(1)).thenReturn(row);when(recipients.currentChat(20,7L)).thenReturn(9191L);when(telegram.canSendTexts()).thenReturn(true);
        when(notices.claim(eq(1L),anyString())).thenReturn(Optional.of(row));when(telegram.sendTextOnceMessageId(9191,"Оплата получена")).thenReturn(Optional.empty());
        doAnswer(call -> {row.put("state",call.getArgument(2));return null;}).when(notices).finish(eq(1L),anyString(),anyString(),any(),any());
        worker.deliver(1);worker.deliver(1);
        verify(telegram,times(1)).sendTextOnceMessageId(9191,"Оплата получена");assertEquals("UNKNOWN",row.get("state"));
    }
    @Test void disabledTransportAndMissingChatRemainPendingWithoutAnySendAttempt() {
        settings.setEnabled(true);when(notices.row(1)).thenReturn(row("PENDING",null));
        when(recipients.currentChat(20,7L)).thenReturn(null);
        worker.deliver(1);verify(notices).postpone(eq(1L),contains("Не привязан"));verify(notices,never()).claim(anyLong(),anyString());verifyNoInteractions(telegram);
        when(notices.row(2)).thenReturn(row("PENDING",9191L));when(recipients.currentChat(20,7L)).thenReturn(9191L);
        worker.deliver(2);verify(notices).postpone(eq(2L),contains("выключена"));verify(telegram,never()).sendTextOnceMessageId(anyLong(),anyString());
    }
    @Test void commonInvoiceResolvesItsResponsibleManagersAndIncludesTheExactAmount() {
        var row=Map.<String,Object>of("target_kind","COMMON_INVOICE","target_id",55L,"paid_at",java.time.LocalDateTime.now(),"number","PB-55-12345678","amount_kopecks",10050L);
        when(documents.row("doc")).thenReturn(row);when(common.staffContext(55)).thenReturn(new BankPaperInvoices.StaffContext(List.of(7L,8L),List.of(42L,43L),"Общий счёт"));
        when(recipients.forManagers(List.of(7L,8L))).thenReturn(List.of(new PaymentNoticeRecipients.Recipient(1,null,9191L)));
        worker.prepare("doc");
        verify(automation).synchronizeOrder(42);verify(automation).synchronizeOrder(43);
        verify(notices).prepare(eq("doc"),any(),argThat(body -> body.contains("100,50 ₽") && body.contains("PB-55-12345678") && body.contains("Общий счёт №55")));
        verifyNoInteractions(orders,telegram);
    }
    @Test void unpaidDocumentCannotPrepareAnyStaffMessage() {
        when(documents.row("doc")).thenReturn(Map.of("target_kind","ORDER","target_id",42L));worker.prepare("doc");
        verifyNoInteractions(recipients,notices,automation,telegram);
    }
}
