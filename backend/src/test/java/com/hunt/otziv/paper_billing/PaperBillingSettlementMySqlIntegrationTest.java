package com.hunt.otziv.paper_billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import com.hunt.otziv.payments.service.PaymentLinkSettlementService;
import com.hunt.otziv.common_billing.api.BankPaperInvoices;
import com.hunt.otziv.payments.tochka.service.TochkaWebhookJwtVerifier;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class PaperBillingSettlementMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("paper_fixture").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc; PaperBillingStore store; PaperBillingSettlement settlement; PaymentLinkSettlementService finance;
    String id;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds);
        for(String table:List.of("company_billing_payer_audit","company_billing_payer","paper_invoice_bank_receipt","paper_invoice_document","paper_invoice_statement_cursor","payment_links","common_invoices","orders"))
            jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO orders VALUES(42)");
        jdbc.execute("CREATE TABLE payment_links(id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO payment_links VALUES(8)");
        jdbc.execute("CREATE TABLE common_invoices(invoice_id BIGINT PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_348__tochka_paper_documents.sql")).execute(ds);
        store=new PaperBillingStore(jdbc,mock(BankPaperInvoices.class)); id=UUID.randomUUID().toString();
        var service=mock(PaperBillingService.class);
        when(service.payer(anyString())).thenReturn(new Payer("company","Тест","7707083893","773601001",""));
        var settings=new PaperBillingSettings(); settings.setEnabled(true); settings.setBankOperationsEnabled(true);
        finance=mock(PaymentLinkSettlementService.class);
        settlement=new PaperBillingSettlement(store,service,settings,mock(PaperBillingBankClient.class),finance,
                mock(BankPaperInvoices.class),mock(TochkaWebhookJwtVerifier.class),new DataSourceTransactionManager(ds));
        store.insert(id,new Target("ORDER",42L),1,8L,250000,"{}","40802810000000004287/044525104","customer","manager","WHATSAPP:1@g.us","test");
        store.bankId(id,false,"bank-document","WAITING_PAYMENT");
    }
    BankReceipt receipt(String payment,long amount,String inn) {
        return new BankReceipt("customer","40802810000000004287/044525104",payment,inn,amount,"Оплата по счёту "+store.row(id).get("number"));
    }
    @Test void fullTransferAndRepeatedCallbackApplyOnce() {
        var receipt=receipt("p1",250000,"7707083893");
        settlement.accept(receipt); settlement.accept(receipt);
        verify(finance,times(1)).applyVerifiedPaperBankPayment(8L,250000);
        assertEquals("PAID",store.row(id).get("stage"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt",Integer.class));
    }
    @Test void partialThenRemainderCannotBeSummedIntoAutomaticPayment() {
        settlement.accept(receipt("p1",200000,"7707083893"));
        settlement.accept(receipt("p2",50000,"7707083893"));
        verifyNoInteractions(finance);
        assertEquals("NEEDS_REVIEW",store.row(id).get("stage"));
        assertNull(store.row(id).get("paid_at"));
    }
    @Test void wrongPayerAndDuplicateFullTransfersRequireReview() {
        settlement.accept(receipt("p1",250000,"380124742639"));
        settlement.accept(receipt("p2",250000,"7707083893"));
        verifyNoInteractions(finance);
        assertEquals("NEEDS_REVIEW",store.row(id).get("stage"));
    }
    @Test void financialFailureRollsBackAndKeepsReceiptForReview() {
        doThrow(PaperBillingModels.problem("changed amount")).when(finance).applyVerifiedPaperBankPayment(8L,250000);
        settlement.accept(receipt("p1",250000,"7707083893"));
        assertEquals("NEEDS_REVIEW",store.row(id).get("stage"));
        assertNull(store.row(id).get("paid_at"));
        assertEquals("NEEDS_REVIEW",jdbc.queryForObject("SELECT state FROM paper_invoice_bank_receipt",String.class));
    }
    @Test void waitingPaymentsDoNotStarveNewDocuments() {
        String next=UUID.randomUUID().toString();
        store.insert(next,new Target("ORDER",43L),1,null,250000,"{}","40802810000000004287/044525104","customer","manager","WHATSAPP:1@g.us","test");
        assertEquals(List.of(next),store.pending());
    }
    @Test void bankResponseCannotClearConcurrentPaymentReview() {
        store.error(id,"NEEDS_REVIEW","Payment mismatch");
        store.bankId(id,true,"act-document","ACT_CREATED");
        assertEquals("NEEDS_REVIEW",store.row(id).get("stage"));
        assertEquals("act-document",store.row(id).get("bank_act_id"));
        store.error(id,"ACT_CREATED","Retry PDF");
        assertEquals("NEEDS_REVIEW",store.row(id).get("stage"));
    }
    @Test void paidDocumentCannotBecomeUnpaidReview() {
        settlement.accept(receipt("p1",250000,"7707083893"));
        store.error(id,"NEEDS_REVIEW","Concurrent failure");
        assertEquals("PAID",store.row(id).get("stage"));
        assertNotNull(store.row(id).get("paid_at"));
    }
}
