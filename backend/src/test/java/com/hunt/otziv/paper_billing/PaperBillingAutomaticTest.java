package com.hunt.otziv.paper_billing;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import com.hunt.otziv.p_products.api.PaperInvoiceOrderData;
import com.hunt.otziv.c_companies.api.PaperInvoiceCompanyData;
import com.hunt.otziv.common_billing.api.BankPaperInvoiceParts;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

class PaperBillingAutomaticTest {
    PaperBillingAutomaticTest(){when(parts.parentForOrder(anyLong())).thenReturn(null);}
    final PaperBillingSettings settings=new PaperBillingSettings();
    final PaperBillingBankClient bank=mock(PaperBillingBankClient.class);
    final PaperBillingService service=mock(PaperBillingService.class);
    final PaperBillingStore store=mock(PaperBillingStore.class);
    final BillingPayerService payers=mock(BillingPayerService.class);
    final PaperInvoiceOrderData orders=mock(PaperInvoiceOrderData.class);
    final BankPaperInvoiceParts parts=mock(BankPaperInvoiceParts.class);
    final PaperInvoiceCompanyData companies=mock(PaperInvoiceCompanyData.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final Payer payer=new Payer("company","Тест","7707083893","773601001","");
    PaperBillingAutomatic worker(){settings.setEnabled(true);settings.setBankOperationsEnabled(true);when(bank.configured()).thenReturn(true);when(store.jdbc()).thenReturn(jdbc);when(jdbc.queryForObject(anyString(),eq(Long.class),anyString(),anyLong())).thenReturn(0L);return new PaperBillingAutomatic(settings,bank,service,store,payers,orders,parts,companies);}
    BillingPayerService.Choice choice(String id,long company){return new BillingPayerService.Choice(id,company,"shared-profile",payer,true,1,null);}
    @Test void repeatedStandaloneUsesPersistedPayerAndRestoresCallingSecurityContext() {
        var context=SecurityContextHolder.getContext();var rule=new PaperInvoiceOrderData.AutomaticOrder(42,1,"saved");
        when(orders.automaticCandidates(0,100)).thenReturn(List.of(rule));when(store.latest(any())).thenReturn(Optional.empty());
        when(companies.read(1)).thenReturn(Map.of("paper_billing_automatic",true,"paper_billing_payer_choice_id","saved"));when(payers.choices(List.of(1L))).thenReturn(List.of(choice("saved",1)));
        worker().tick();verify(service).automaticIssue(eq(new Target("ORDER",42L)),argThat(r->r.payer().equals(payer)),any());assertSame(context,SecurityContextHolder.getContext());
    }
    @Test void commonOrdersWithTheSameImmutableProfileAreGroupedAcrossCompanies() {
        when(orders.automaticCandidates(0,100)).thenReturn(List.of(new PaperInvoiceOrderData.AutomaticOrder(1,1,"a")));
        when(parts.parentForOrder(1)).thenReturn(8L);when(parts.positions(8)).thenReturn(List.of(new BankPaperInvoiceParts.Position(1,1,"A",10000,true,false,null),new BankPaperInvoiceParts.Position(2,2,"B",10000,true,false,null),new BankPaperInvoiceParts.Position(3,3,"C",10000,true,false,null)));
        for(long i=1;i<=3;i++){when(orders.automaticOrder(i)).thenReturn(new PaperInvoiceOrderData.AutomaticOrder(i,i,"saved-"+i));when(companies.read(i)).thenReturn(Map.of("paper_billing_automatic",i!=3));if(i<3)when(payers.choices(List.of(i))).thenReturn(List.of(choice("saved-"+i,i)));}
        worker().tick();verify(service,times(1)).automaticIssue(eq(new Target("COMMON_INVOICE",8L)),argThat(r->r.orderIds().equals(List.of(1L,2L)) && r.requestKey().startsWith("auto-")),any());
    }
    @Test void deletedPayerCannotBeRecreatedByAutomaticIssuance() {
        when(orders.automaticCandidates(0,100)).thenReturn(List.of(new PaperInvoiceOrderData.AutomaticOrder(42,1,"deleted")));when(store.latest(any())).thenReturn(Optional.empty());
        when(companies.read(1)).thenReturn(Map.of("paper_billing_automatic",true,"paper_billing_payer_choice_id","deleted"));when(payers.choices(List.of(1L))).thenReturn(List.of());
        worker().tick();verify(service,never()).automaticIssue(any(),any(),any());verify(payers,never()).remember(anyLong(),any(),anyString());verify(jdbc).update(contains("INSERT INTO paper_invoice_automatic_error"),eq("ORDER"),eq(42L),contains("удалены"));
    }
}
