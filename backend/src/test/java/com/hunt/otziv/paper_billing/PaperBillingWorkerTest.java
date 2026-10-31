package com.hunt.otziv.paper_billing;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaperBillingWorkerTest {
    @Test void uncertainCreateCannotBeSubmittedAgain() {
        var store=mock(PaperBillingStore.class); var service=mock(PaperBillingService.class);
        var bank=mock(PaperBillingBankClient.class);
        var worker=new PaperBillingWorker(store,service,new PaperBillingSettings(),bank,mock(PaperBillingDelivery.class),mock(com.hunt.otziv.common_billing.api.BankPaperInvoices.class));
        var r=new HashMap<String,Object>(); r.put("stage","PENDING"); r.put("payer_json","{}");
        when(store.row("doc")).thenReturn(r);
        when(store.move("doc","PENDING","INVOICE_CREATING")).thenAnswer(call -> {r.put("stage","INVOICE_CREATING"); return true;});
        when(bank.create(eq(r),any(),eq(false))).thenThrow(new IllegalStateException("lost response"));
        worker.process("doc"); worker.process("doc");
        verify(bank,times(1)).create(eq(r),any(),eq(false));
        verify(store).error(eq("doc"),eq("INVOICE_CREATING_UNKNOWN"),anyString());
    }
}
