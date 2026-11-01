package com.hunt.otziv.paper_billing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.TestingAuthenticationToken;
class PaperBillingWholeTargetGuardTest {
    @Test void anOldWholeInvoiceRequestCannotDuplicateAConcurrentSubset() {
        var store=mock(PaperBillingStore.class);var targets=mock(PaperBillingTargets.class);var bank=mock(PaperBillingBankClient.class);
        var parts=mock(com.hunt.otziv.common_billing.api.BankPaperInvoiceParts.class);var settings=new PaperBillingSettings();settings.setBankOperationsEnabled(true);
        when(bank.configured()).thenReturn(true);when(targets.company(any(),any())).thenReturn(1L);when(targets.prepare(any(),any())).thenReturn(new PaperBillingTargets.Prepared(1,null,10000));
        when(parts.partIds(8)).thenReturn(List.of(),List.of(1L));
        var service=new PaperBillingService(store,targets,settings,bank,new com.fasterxml.jackson.databind.ObjectMapper(),mock(org.springframework.transaction.PlatformTransactionManager.class));ReflectionTestUtils.setField(service,"parts",parts);
        assertThrows(RuntimeException.class,()->service.issue(new Target("COMMON_INVOICE",8L),new IssueRequest(new Payer("company","Test","7707083893","773601001",""),true),new TestingAuthenticationToken("admin","","ROLE_ADMIN")));
        verify(store,never()).insert(anyString(),any(),anyLong(),any(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
}
