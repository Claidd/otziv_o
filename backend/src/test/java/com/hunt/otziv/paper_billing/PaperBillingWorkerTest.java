package com.hunt.otziv.paper_billing;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaperBillingWorkerTest {
    @Test void uncertainCreateCannotBeSubmittedAgain() {
        var store=mock(PaperBillingStore.class); var service=mock(PaperBillingService.class);
        var bank=mock(PaperBillingBankClient.class);
        var worker=new PaperBillingWorker(store,service,new PaperBillingSettings(),bank,mock(PaperBillingDelivery.class),mock(com.hunt.otziv.common_billing.api.BankPaperInvoices.class),mock(PaperBillingPdfSignature.class));
        var r=new HashMap<String,Object>(); r.put("stage","PENDING"); r.put("payer_json","{}");
        when(store.row("doc")).thenReturn(r);
        when(store.move("doc","PENDING","INVOICE_CREATING")).thenAnswer(call -> {r.put("stage","INVOICE_CREATING"); return true;});
        when(bank.create(eq(r),any(),eq(false))).thenThrow(new IllegalStateException("lost response"));
        worker.process("doc"); worker.process("doc");
        verify(bank,times(1)).create(eq(r),any(),eq(false));
        verify(store).error(eq("doc"),eq("INVOICE_CREATING_UNKNOWN"),anyString());
    }
    @Test void uncertainIntroIsNeverResentAndNoDocumentsAreSentAheadOfIt() {
        var store=mock(PaperBillingStore.class);var delivery=mock(PaperBillingDelivery.class);
        var settings=new PaperBillingSettings();settings.setDeliveryEnabled(true);
        var row=new HashMap<String,Object>();row.put("stage","FILES_READY");row.put("message_delivery","PENDING");
        when(store.row("doc")).thenReturn(row);when(store.claimMessage("doc")).thenReturn(true);
        when(delivery.sendIntro(row)).thenReturn(com.hunt.otziv.client_messages.dto.ClientMessageSendResult.failed("operation_unknown","unknown"));
        doAnswer(call -> {row.put("message_delivery",call.getArgument(1));return null;}).when(store).messageDelivery(eq("doc"),anyString(),any());
        var worker=new PaperBillingWorker(store,mock(PaperBillingService.class),settings,mock(PaperBillingBankClient.class),delivery,mock(com.hunt.otziv.common_billing.api.BankPaperInvoices.class),mock(PaperBillingPdfSignature.class));
        worker.process("doc");worker.process("doc");
        verify(delivery,times(1)).sendIntro(row);verify(delivery,never()).send(any(),anyBoolean());
        assertEquals("UNKNOWN",row.get("message_delivery"));
    }
}
