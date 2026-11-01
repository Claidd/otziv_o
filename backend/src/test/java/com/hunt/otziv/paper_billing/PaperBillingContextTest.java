package com.hunt.otziv.paper_billing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.TestingAuthenticationToken;
class PaperBillingContextTest {
    @Test void rootsAndStandaloneOrdersHaveNullableParentWithoutNumericUnboxing() {
        var store=mock(PaperBillingStore.class);var targets=mock(PaperBillingTargets.class);var bank=mock(PaperBillingBankClient.class);
        var parts=mock(com.hunt.otziv.common_billing.api.BankPaperInvoiceParts.class);when(parts.parentForOrder(anyLong())).thenReturn(null);
        when(targets.company(any(),any())).thenReturn(1L);when(targets.payerCompanies(any(),anyLong())).thenReturn(List.of(1L));when(targets.companyInfo(1)).thenReturn(Map.of("company_title","Test"));when(store.latest(any())).thenReturn(Optional.empty());
        var service=new PaperBillingService(store,targets,new PaperBillingSettings(),bank,new com.fasterxml.jackson.databind.ObjectMapper(),mock(org.springframework.transaction.PlatformTransactionManager.class));ReflectionTestUtils.setField(service,"parts",parts);
        var auth=new TestingAuthenticationToken("manager","","ROLE_MANAGER");
        assertNull(service.context(new Target("COMMON_INVOICE",8L),auth).parentInvoiceId());assertNull(service.context(new Target("ORDER",42L),auth).parentInvoiceId());
        when(parts.parentForOrder(42)).thenReturn(8L);assertEquals(8L,service.context(new Target("ORDER",42L),auth).parentInvoiceId());
    }
}
