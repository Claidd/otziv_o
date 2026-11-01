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
        for(String table:List.of("paper_invoice_staff_notice","company_billing_payer_audit","company_billing_payer","paper_invoice_bank_receipt","paper_invoice_document","paper_invoice_statement_cursor","payment_links","common_invoices","orders"))
            jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO orders VALUES(42)");
        jdbc.execute("CREATE TABLE payment_links(id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO payment_links VALUES(8)");
        jdbc.execute("CREATE TABLE common_invoices(invoice_id BIGINT PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_348__tochka_paper_documents.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_349__paper_document_service_snapshot.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_350__paper_document_visual_signature.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_351__paper_document_intro_delivery.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_352__paper_invoice_delivery_and_staff_notices.sql")).execute(ds);
        store=new PaperBillingStore(jdbc,mock(BankPaperInvoices.class)); id=UUID.randomUUID().toString();
        var service=mock(PaperBillingService.class);
        when(service.payer(anyString())).thenReturn(new Payer("company","Тест","7707083893","773601001",""));
        var settings=new PaperBillingSettings(); settings.setEnabled(true); settings.setBankOperationsEnabled(true);
        finance=mock(PaymentLinkSettlementService.class);
        settlement=new PaperBillingSettlement(store,service,settings,mock(PaperBillingBankClient.class),finance,
                mock(BankPaperInvoices.class),mock(TochkaWebhookJwtVerifier.class),new DataSourceTransactionManager(ds),mock(com.hunt.otziv.client_messages.api.PaperInvoiceAutomation.class));
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
    @Test void simultaneousDuplicateWebhooksApplyThePaymentOnlyOnce() throws Exception {
        concurrently(() -> settlement.accept(receipt("same",250000,"7707083893")),
                () -> settlement.accept(receipt("same",250000,"7707083893")));
        verify(finance,times(1)).applyVerifiedPaperBankPayment(8L,250000);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt",Integer.class));
        assertEquals("PAID",store.row(id).get("stage"));
    }
    @Test void simultaneousDifferentFullTransfersCannotDoubleCloseTheOrder() throws Exception {
        concurrently(() -> settlement.accept(receipt("first",250000,"7707083893")),
                () -> settlement.accept(receipt("second",250000,"7707083893")));
        verify(finance,times(1)).applyVerifiedPaperBankPayment(8L,250000);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt WHERE state='APPLIED'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt WHERE state='NEEDS_REVIEW'",Integer.class));
        assertEquals("PAID",store.row(id).get("stage"));
    }
    @Test void lateBackgroundResultsCannotReopenAPaidDocument() {
        settlement.accept(receipt("p1",250000,"7707083893"));
        store.bankId(id,true,"late-act","ACT_CREATED");store.error(id,"ACT_CREATED","late failure");
        assertEquals("PAID",store.row(id).get("stage"));assertNull(store.row(id).get("error_text"));
    }
    @Test void commonDocumentBindingIsVisibleToTheMutationGuardBeforeDelivery() {
        jdbc.execute("CREATE TABLE common_invoice_orders(order_id BIGINT,invoice_id BIGINT,active_membership TINYINT)");
        try {
            jdbc.update("INSERT INTO common_invoices(invoice_id,paper_bank_document_id) VALUES(5,?)",id);
            jdbc.update("INSERT INTO common_invoice_orders VALUES(42,5,1)");
            var guard=new com.hunt.otziv.common_billing.service.BankPaperInvoiceBindingsService(jdbc);
            assertTrue(guard.hasDocument(42));jdbc.update("UPDATE common_invoice_orders SET active_membership=0");assertFalse(guard.hasDocument(42));
        } finally {jdbc.execute("DROP TABLE common_invoice_orders");}
    }
    @Test void commonBindingGuardSeesDocumentsCommittedAfterAnEarlierRepeatableReadSnapshot() {
        jdbc.execute("CREATE TABLE common_invoice_orders(order_id BIGINT,invoice_id BIGINT,active_membership TINYINT)");
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            jdbc.update("INSERT INTO common_invoices(invoice_id) VALUES(5)");jdbc.update("INSERT INTO common_invoice_orders VALUES(42,5,1)");
            var manager=new DataSourceTransactionManager(jdbc.getDataSource());
            var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tx.executeWithoutResult(status -> {
                assertNull(jdbc.queryForObject("SELECT paper_bank_document_id FROM common_invoices WHERE invoice_id=5",String.class));
                java.util.concurrent.CompletableFuture.runAsync(()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(other -> {
                    jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=42 FOR UPDATE",Long.class);
                    jdbc.update("UPDATE common_invoices SET paper_bank_document_id=? WHERE invoice_id=5",id);
                }),executor).orTimeout(10,java.util.concurrent.TimeUnit.SECONDS).join();
                jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=42 FOR UPDATE",Long.class);
                assertTrue(new com.hunt.otziv.common_billing.service.BankPaperInvoiceBindingsService(jdbc).hasDocument(42));
            });
        } finally {jdbc.execute("DROP TABLE common_invoice_orders");}
    }

    @Test void concurrentIssueRequestsCreateOneDurableDocumentAndReturnTheSameNumber() throws Exception {
        jdbc.update("DELETE FROM paper_invoice_document WHERE id=?",id);
        var targets=mock(PaperBillingTargets.class);var bank=mock(PaperBillingBankClient.class);var settings=new PaperBillingSettings();settings.setBankOperationsEnabled(true);
        var auth=new org.springframework.security.authentication.TestingAuthenticationToken("tester","","ROLE_ADMIN");var target=new Target("ORDER",42L);
        when(bank.configured()).thenReturn(true);when(bank.supplierAccount()).thenReturn("40802810000000004287/044525104");when(bank.customer()).thenReturn("customer");when(bank.serviceName()).thenReturn("Service");when(bank.vatKind()).thenReturn("without_nds");
        when(targets.company(target,auth)).thenReturn(1L);when(targets.companyInfo(1)).thenReturn(Map.of("company_group_id","local","company_url_chat","https://chat.whatsapp.com/local","client_id","fixture"));when(targets.deliveryClient(target,"fixture")).thenReturn("fixture");
        when(targets.prepare(target,auth)).thenAnswer(call->{jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=42 FOR UPDATE",Long.class);return new PaperBillingTargets.Prepared(1,8L,250000);});
        var service=new PaperBillingService(store,targets,settings,bank,new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
        var result=new java.util.concurrent.ConcurrentLinkedQueue<Document>();var request=new IssueRequest(new Payer("company","Test","7707083893","773601001",""),true);
        concurrently(()->result.add(service.issue(target,request,auth)),()->result.add(service.issue(target,request,auth)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_document",Integer.class));
        assertEquals(1,result.stream().map(Document::id).distinct().count());assertEquals(2,result.size());
        verify(bank,never()).create(anyMap(),any(),anyBoolean());
    }
    @Test void companyChangedDuringPreparationCannotUseThePreviousCompanysPayerOrChat() {
        jdbc.update("DELETE FROM paper_invoice_document WHERE id=?",id);
        var targets=mock(PaperBillingTargets.class);var bank=mock(PaperBillingBankClient.class);var settings=new PaperBillingSettings();settings.setBankOperationsEnabled(true);
        var auth=new org.springframework.security.authentication.TestingAuthenticationToken("tester","","ROLE_ADMIN");var target=new Target("ORDER",42L);
        when(bank.configured()).thenReturn(true);when(targets.company(target,auth)).thenReturn(1L);when(targets.prepare(target,auth)).thenReturn(new PaperBillingTargets.Prepared(2,8L,250000));
        var service=new PaperBillingService(store,targets,settings,bank,new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.issue(target,new IssueRequest(new Payer("company","Test","7707083893","773601001",""),true),auth));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_document",Integer.class));verify(targets,never()).companyInfo(anyLong());
    }
    @Test void deliveryCompletionAndPaymentCanRaceWithoutLosingEitherCommit() throws Exception {
        jdbc.execute("ALTER TABLE payment_links ADD payment_method VARCHAR(32),ADD status VARCHAR(32),ADD paper_invoice_issued_at DATETIME(6),ADD initiated_at DATETIME(6)");
        jdbc.update("UPDATE payment_links SET payment_method='OWNER_PAPER_INVOICE',status='CREATED' WHERE id=8");
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',message_delivery='SENT',invoice_delivery='SENT',act_delivery='SENT' WHERE id=?",id);
        var targets=mock(PaperBillingTargets.class);
        doAnswer(call->{jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=42 FOR UPDATE",Long.class);return null;}).when(targets).lockDelivery(new Target("ORDER",42L));
        var service=new PaperBillingService(store,targets,new PaperBillingSettings(),mock(PaperBillingBankClient.class),new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
        concurrently(()->service.completeDelivery(id),()->settlement.accept(receipt("racing",250000,"7707083893")));
        assertEquals("PAID",store.row(id).get("stage"));assertNotNull(store.row(id).get("paid_at"));assertNotNull(store.row(id).get("delivery_completed_at"));
        verify(finance,times(1)).applyVerifiedPaperBankPayment(8L,250000);verify(targets,times(1)).delivered(new Target("ORDER",42L),id);
    }

    private void concurrently(Runnable first,Runnable second) throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{await(start);first.run();});var b=executor.submit(()->{await(start);second.run();});
            start.countDown();a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timeout");}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
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
    @Test void originalPdfIsPreservedAndSignatureCannotChangeWithinASet() {
        byte[] original={1,2,3},signed={4,5,6};String hash="a".repeat(64);
        store.pdf(id,false,new PaperBillingPdfSignature.Pdf(original,signed,hash));
        var row=store.row(id);
        assertArrayEquals(original,(byte[])row.get("invoice_original_pdf"));
        assertArrayEquals(signed,(byte[])row.get("invoice_pdf"));
        assertThrows(IllegalStateException.class,()->store.pdf(id,true,new PaperBillingPdfSignature.Pdf(original,signed,"b".repeat(64))));
        assertNull(store.row(id).get("act_pdf"));
        store.pdf(id,true,new PaperBillingPdfSignature.Pdf(original,signed,hash));
        assertArrayEquals(original,(byte[])store.row(id).get("act_original_pdf"));
    }
    @Test void staffNoticesAreDurableDeduplicatedAndOnlyCreatedForAppliedPayment() {
        var notices=new PaperBillingNoticeStore(jdbc);
        assertTrue(notices.unprepared().isEmpty());
        settlement.accept(receipt("staff-payment",250000,"7707083893"));
        assertEquals(List.of(id),notices.unprepared());
        var recipients=List.of(new com.hunt.otziv.u_users.api.PaymentNoticeRecipients.Recipient(1,null,9191L),
                new com.hunt.otziv.u_users.api.PaymentNoticeRecipients.Recipient(20,7L,-500L));
        notices.prepare(id,recipients,"Оплата подтверждена");notices.prepare(id,recipients,"Другой повтор");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_staff_notice",Integer.class));
        assertTrue(notices.unprepared().isEmpty());
        long notice=notices.due().getFirst();
        assertTrue(notices.claim(notice,"first").isPresent());assertTrue(notices.claim(notice,"second").isEmpty());
        notices.finish(notice,"second","SENT","wrong",null);
        assertEquals("SENDING",notices.row(notice).get("state"));
        notices.finish(notice,"first","SENT","100",null);
        assertEquals("SENT",notices.row(notice).get("state"));
        assertTrue(notices.claim(notice,"again").isEmpty());
        settlement.accept(receipt("staff-payment",250000,"7707083893"));
        verify(finance,times(1)).applyVerifiedPaperBankPayment(8L,250000);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_staff_notice",Integer.class));
    }
    @Test void failedFinancialTransactionAndMissingAppliedEvidenceCannotNotifyStaff() {
        var notices=new PaperBillingNoticeStore(jdbc);
        doThrow(PaperBillingModels.problem("financial failure")).when(finance).applyVerifiedPaperBankPayment(8L,250000);
        settlement.accept(receipt("staff-failure",250000,"7707083893"));
        assertTrue(notices.unprepared().isEmpty());
        notices.prepare(id,List.of(new com.hunt.otziv.u_users.api.PaymentNoticeRecipients.Recipient(1,null,9191L)),"No send");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_staff_notice",Integer.class));
    }
    @Test void abandonedStaffSendBecomesUnknownAndCannotBeRetriedBlindly() {
        settlement.accept(receipt("unknown-staff",250000,"7707083893"));
        var notices=new PaperBillingNoticeStore(jdbc);
        notices.prepare(id,List.of(new com.hunt.otziv.u_users.api.PaymentNoticeRecipients.Recipient(1,null,9191L)),"Paid");
        long notice=notices.due().getFirst();assertTrue(notices.claim(notice,"crashed").isPresent());
        jdbc.update("UPDATE paper_invoice_staff_notice SET updated_at=DATE_SUB(NOW(),INTERVAL 6 MINUTE) WHERE notice_id=?",notice);
        assertTrue(notices.due().isEmpty());assertEquals("UNKNOWN",notices.row(notice).get("state"));
        assertTrue(notices.claim(notice,"retry").isEmpty());
    }
    @Test void completingDeliveredDocumentsIsAtomicAndCannotRestartTheCycleTwice() {
        jdbc.execute("ALTER TABLE payment_links ADD payment_method VARCHAR(32),ADD status VARCHAR(32),ADD paper_invoice_issued_at DATETIME(6),ADD initiated_at DATETIME(6)");
        jdbc.update("UPDATE payment_links SET payment_method='OWNER_PAPER_INVOICE',status='CREATED' WHERE id=8");
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',message_delivery='SENT',invoice_delivery='SENT',act_delivery='SENT' WHERE id=?",id);
        var targets=mock(PaperBillingTargets.class);
        var service=new PaperBillingService(store,targets,new PaperBillingSettings(),mock(PaperBillingBankClient.class),new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
        service.completeDelivery(id);service.completeDelivery(id);
        verify(targets,times(1)).delivered(new Target("ORDER",42L),id);
        assertEquals("WAITING_PAYMENT",store.row(id).get("stage"));assertNotNull(store.row(id).get("delivery_completed_at"));
        assertNotNull(jdbc.queryForObject("SELECT paper_invoice_issued_at FROM payment_links WHERE id=8",java.sql.Timestamp.class));
    }
    @Test void failedQueueSynchronizationRollsBackDeliveryAndOrderChanges() {
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',message_delivery='SENT',invoice_delivery='SENT',act_delivery='SENT' WHERE id=?",id);
        var targets=mock(PaperBillingTargets.class);
        doAnswer(call -> {
            jdbc.update("UPDATE paper_invoice_document SET error_text='must roll back' WHERE id=?",id);
            throw new IllegalStateException("queue unavailable");
        }).when(targets).delivered(new Target("ORDER",42L),id);
        var service=new PaperBillingService(store,targets,new PaperBillingSettings(),mock(PaperBillingBankClient.class),new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(IllegalStateException.class,()->service.completeDelivery(id));
        assertNull(store.row(id).get("delivery_completed_at"));assertNull(store.row(id).get("error_text"));
        assertEquals("FILES_READY",store.row(id).get("stage"));
    }
    @Test void unsuccessfulPdfDeliveryCannotChangeTheOrderOrStartReminders() {
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',message_delivery='SENT',invoice_delivery='SENT',act_delivery='UNKNOWN' WHERE id=?",id);
        var targets=mock(PaperBillingTargets.class);
        var service=new PaperBillingService(store,targets,new PaperBillingSettings(),mock(PaperBillingBankClient.class),new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.completeDelivery(id));
        verify(targets,never()).delivered(any(),any());assertNull(store.row(id).get("delivery_completed_at"));
    }
}
