package com.hunt.otziv.paper_billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.payments.tochka.config.TochkaPaymentProperties;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PaperBillingCancellationBankContractTest {
    RestTemplate rest=new RestTemplate();MockRestServiceServer server=MockRestServiceServer.createServer(rest);
    PaperBillingBankClient bank;
    final Map<String,Object> document=Map.of("customer_code","LOCAL0001","bank_invoice_id","invoice-id","bank_act_id","act-id");
    @BeforeEach void setup() {
        var settings=new PaperBillingSettings();settings.setEnabled(true);settings.setAccountLast4("6542");settings.setFixtureUrl("http://paper-invoice-fixture:8099");
        var env=new MockEnvironment();env.setActiveProfiles("paper-invoice-fixture");
        bank=new PaperBillingBankClient(rest,new ObjectMapper(),new TochkaPaymentProperties(),settings,env);
    }
    @AfterEach void verify() {server.verify();}
    @Test void readsTheDocumentPaymentStatusAndDeletesBothExactIds() {
        server.expect(requestTo("http://paper-invoice-fixture:8099/invoice/v1.0/bills/LOCAL0001/invoice-id/payment-status"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"Data\":{\"paymentStatus\":\"payment_waiting\"}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paper-invoice-fixture:8099/invoice/v1.0/closing-documents/LOCAL0001/act-id"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess("{\"Data\":{\"result\":true}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paper-invoice-fixture:8099/invoice/v1.0/bills/LOCAL0001/invoice-id"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess("{\"Data\":{\"result\":true}}",MediaType.APPLICATION_JSON));
        assertEquals("payment_waiting",bank.invoicePaymentStatus(document));bank.deleteDocument(document,true);bank.deleteDocument(document,false);
    }
    @Test void confirmedAbsenceIsAnIdempotentDeleteReceipt() {
        server.expect(requestTo("http://paper-invoice-fixture:8099/invoice/v1.0/bills/LOCAL0001/invoice-id"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withResourceNotFound());
        assertDoesNotThrow(()->bank.deleteDocument(document,false));
    }
    @Test void falseResultOrForbiddenCannotBeTreatedAsCancellation() {
        server.expect(anything()).andRespond(withSuccess("{\"Data\":{\"result\":false}}",MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThrows(RuntimeException.class,()->bank.deleteDocument(document,true));assertThrows(RuntimeException.class,()->bank.deleteDocument(document,false));
    }
    @Test void anotherCustomerOrPathFragmentNeverReachesTheBank() {
        assertThrows(RuntimeException.class,()->bank.deleteDocument(Map.of("customer_code","another","bank_invoice_id","id"),false));
        assertThrows(RuntimeException.class,()->bank.deleteDocument(Map.of("customer_code","LOCAL0001","bank_invoice_id","../id"),false));
    }
}
