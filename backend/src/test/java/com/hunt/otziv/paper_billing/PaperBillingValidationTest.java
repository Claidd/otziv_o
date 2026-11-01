package com.hunt.otziv.paper_billing;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.hunt.otziv.paper_billing.PaperBillingModels.*;

class PaperBillingValidationTest {
    @Test void statementWindowHandlesServerDateAheadOfBankDate() throws Exception {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var store=org.mockito.Mockito.mock(PaperBillingStore.class);
        var bank=org.mockito.Mockito.mock(PaperBillingBankClient.class);
        org.mockito.Mockito.when(store.jdbc()).thenReturn(jdbc);
        org.mockito.Mockito.when(bank.customer()).thenReturn("customer");
        var today=java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"));
        String account="40802810000000004287/044525104";
        org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(account)))
                .thenReturn(java.util.Map.of("next_date",today.plusDays(1)));
        org.mockito.Mockito.when(bank.call(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(org.springframework.http.HttpMethod.POST),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    var data=(java.util.Map<?,?>)((java.util.Map<?,?>)call.getArgument(2)).get("Data");
                    var statement=(java.util.Map<?,?>)data.get("Statement");
                    assertEquals(today.toString(),statement.get("startDateTime"));
                    assertEquals(today.toString(),statement.get("endDateTime"));
                    return new ObjectMapper().readTree("{\"Data\":{\"Statement\":{\"statementId\":\"statement-1\"}}}");
                });
        new PaperBillingReconciliation(store,bank,new PaperBillingSettings(),org.mockito.Mockito.mock(PaperBillingSettlement.class))
                .account(java.util.Map.of("supplier_account",account,"customer_code","customer","first_date",today.plusDays(1)));
        org.mockito.Mockito.verify(bank).call(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(org.springframework.http.HttpMethod.POST),org.mockito.ArgumentMatchers.any());
    }
    @Test void acceptsOfficialCompanyAndIndividualChecksums() {
        assertTrue(validInn("7707083893"));
        assertTrue(validInn("380124742639"));
        assertFalse(validInn("7707083894"));
        assertFalse(validInn("0000000000"));
        assertFalse(validInn("123"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> new Payer("ip","ИП Тест","7707083893","","").validate());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> new Payer("ip","ИП Тест","380124742639","123456789","").validate());
    }
    @Test void statementRequiresBookedCreditAndStableBankPaymentIdentity() throws Exception {
        var json=new ObjectMapper();
        var tx=json.readTree("""
            {"paymentId":"bank-42","creditDebitIndicator":"Credit","status":"Booked",
            "transactionTypeCode":"Платежное поручение","description":"Оплата счёта PB-1-1234ABCD",
            "Amount":{"amount":"2500.00","currency":"RUB"},"DebtorParty":{"inn":"7707083893"}}
            """);
        var result=PaperBillingReconciliation.fromStatement(tx,"customer","account");
        assertNotNull(result); assertEquals(250000,result.amountKopecks()); assertEquals("bank-42",result.paymentId());
        for(String field:new String[]{"status","creditDebitIndicator","paymentId","transactionTypeCode"}) {
            var altered=tx.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)altered).put(field,"");
            assertNull(PaperBillingReconciliation.fromStatement(altered,"customer","account"),field);
        }
    }
}
