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
        for(String table:List.of("common_invoice_paper_part_orders","common_invoice_paper_parts","paper_invoice_files","paper_invoice_event","paper_invoice_staff_notice","company_billing_payer_audit","company_billing_payer","paper_invoice_bank_receipt","paper_invoice_document","paper_invoice_statement_cursor","payment_links","common_invoices","orders"))
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
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_353__paper_invoice_cancellation.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_354__paper_cancel_ordinary_invoice.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_356__common_invoice_paper_parts.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_358__paper_part_reminders.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_363__paper_invoice_payment_reference.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_364__paper_files_and_delivery_backoff.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_387__paper_invoice_replacement_mode.sql")).execute(ds);
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

    @Test void unavailableGatewayBacksOffWithoutChangingFilesOrWritingEveryPoll() {
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',message_delivery='SENT' WHERE id=?",id);
        store.pdf(id,false,new PaperBillingPdfSignature.Pdf(new byte[]{1,2,3},new byte[]{4,5},"signature"));
        var settings=new PaperBillingSettings();settings.setDeliveryEnabled(true);
        var delivery=mock(PaperBillingDelivery.class);
        when(delivery.send(anyMap(),eq(false))).thenReturn(com.hunt.otziv.client_messages.dto.ClientMessageSendResult.failed("gateway_not_ready","Unavailable"));
        var worker=new PaperBillingWorker(store,mock(PaperBillingService.class),settings,mock(PaperBillingBankClient.class),delivery,mock(PaperBillingPdfSignature.class));
        worker.process(id);
        var after=store.row(id);
        for(int i=0;i<20;i++) {assertFalse(store.pending().contains(id));worker.process(id);}
        verify(delivery,times(1)).send(anyMap(),eq(false));
        assertEquals(after.get("updated_at"),store.row(id).get("updated_at"));
        assertEquals(1,((Number)store.row(id).get("work_failures")).intValue());
        assertTrue(jdbc.queryForObject("SELECT work_next_attempt_at>DATE_ADD(NOW(6),INTERVAL 45 SECOND) FROM paper_invoice_document WHERE id=?",Boolean.class,id));
        assertArrayEquals(new byte[]{4,5},(byte[])store.row(id).get("invoice_pdf"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='paper_invoice_document' AND data_type IN ('blob','mediumblob','longblob')",Integer.class));
        jdbc.update("UPDATE paper_invoice_document SET work_next_attempt_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",id);
        worker.process(id);
        verify(delivery,times(2)).send(anyMap(),eq(false));
        assertTrue(jdbc.queryForObject("SELECT work_next_attempt_at>DATE_ADD(NOW(6),INTERVAL 105 SECOND) FROM paper_invoice_document WHERE id=?",Boolean.class,id));
    }

    private PaperBillingService manualDeliveryService(PaperBillingTargets targets) {
        return new PaperBillingService(store,targets,new PaperBillingSettings(),mock(PaperBillingBankClient.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    @Test void managerConfirmationClosesAnUnconfirmedFileSendLikeAConfirmedDelivery() {
        jdbc.update("""
            UPDATE paper_invoice_document SET stage='FILES_READY',payment_link_id=NULL,message_delivery='SENT',
            invoice_delivery='UNKNOWN',act_delivery='UNKNOWN',work_failures=2,error_text='Счёт: не подтверждено' WHERE id=?
            """,id);
        var targets=mock(PaperBillingTargets.class);var service=manualDeliveryService(targets);
        var auth=new org.springframework.security.authentication.TestingAuthenticationToken("lika",null,"ROLE_MANAGER");
        var document=service.confirmManualDelivery(id,auth);
        assertEquals("WAITING_PAYMENT",document.stage());assertTrue(document.deliveryCompleted());
        assertEquals("SENT",document.invoiceDelivery());assertEquals("SENT",document.actDelivery());assertEquals("SENT",document.messageDelivery());
        var row=store.row(id);
        assertNull(row.get("error_text"));assertNotNull(row.get("delivery_completed_at"));assertEquals(0,((Number)row.get("work_failures")).intValue());
        var target=new Target("ORDER",42L);
        verify(targets).company(target,auth);verify(targets).lockDelivery(target);verify(targets).delivered(target,id);
        assertTrue(jdbc.queryForObject("SELECT body FROM paper_invoice_event WHERE document_id=? AND event_key='manual-delivery'",String.class,id).contains("lika"));
        // The system never sends these documents again, and a repeated click changes nothing.
        assertFalse(store.pending().contains(id));assertFalse(store.claimDelivery(id,false));assertFalse(store.claimDelivery(id,true));
        service.confirmManualDelivery(id,auth);
        verify(targets,times(1)).delivered(target,id);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_event WHERE document_id=? AND event_key='manual-delivery'",Integer.class,id));
    }
    @Test void confirmationWaitsForASendInFlightAndNeverTouchesACancellation() {
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',payment_link_id=NULL,message_delivery='SENT',invoice_delivery='SENDING',act_delivery='PENDING' WHERE id=?",id);
        var targets=mock(PaperBillingTargets.class);var service=manualDeliveryService(targets);
        var auth=new org.springframework.security.authentication.TestingAuthenticationToken("lika",null,"ROLE_MANAGER");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.confirmManualDelivery(id,auth));
        assertEquals("SENDING",store.row(id).get("invoice_delivery"));assertEquals("PENDING",store.row(id).get("act_delivery"));
        jdbc.update("UPDATE paper_invoice_document SET invoice_delivery='UNKNOWN',cancel_requested_at=NOW(6) WHERE id=?",id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.confirmManualDelivery(id,auth));
        var row=store.row(id);
        assertEquals("UNKNOWN",row.get("invoice_delivery"));assertNull(row.get("delivery_completed_at"));
        verify(targets,never()).delivered(any(),anyString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_event WHERE document_id=? AND event_key='manual-delivery'",Integer.class,id));
    }
    @Test void deliveryLeaseExpiresIndependentlyOfPaymentStatusUpdates() {
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',invoice_delivery='SENDING',delivery_attempted_at=DATE_SUB(NOW(6),INTERVAL 6 MINUTE),updated_at=NOW(6) WHERE id=?",id);
        store.pending();
        assertEquals("UNKNOWN",store.row(id).get("invoice_delivery"));
        assertFalse(store.claimDelivery(id,false));
    }
    @Test void configurationFailureWaitsThirtyMinutesAndLongFailureCreatesOneStaffNotice() {
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',message_delivery='SENT' WHERE id=?",id);
        for(int attempt=0;attempt<7;attempt++) {
            jdbc.update("UPDATE paper_invoice_document SET work_next_attempt_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",id);
            assertTrue(store.claimDelivery(id,false));
            store.delivery(id,false,"PENDING",null);
            store.deliveryFailure(id,"Счёт","not_ready","Клиентские сообщения выключены");
        }
        assertTrue(jdbc.queryForObject("SELECT work_next_attempt_at>DATE_ADD(NOW(6),INTERVAL 29 MINUTE) FROM paper_invoice_document WHERE id=?",Boolean.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_event WHERE document_id=? AND event_key='delivery-attention'",Integer.class,id));
    }
    @Test void paymentArrivingDuringReminderDoesNotLeaveAPaidInvoiceInReview() {
        jdbc.update("UPDATE paper_invoice_document SET target_kind='COMMON_PART',target_id=1,paper_next_reminder_at=DATE_SUB(NOW(6),INTERVAL 1 MINUTE) WHERE id=?",id);
        var settings=new PaperBillingSettings();settings.setEnabled(true);settings.setBankOperationsEnabled(true);settings.setDeliveryEnabled(true);
        var bank=mock(PaperBillingBankClient.class);when(bank.configured()).thenReturn(true);
        var parts=mock(com.hunt.otziv.common_billing.api.BankPaperInvoiceParts.class);when(parts.remindersAllowed(1)).thenReturn(true);when(parts.nextReminder(1)).thenReturn(java.time.LocalDateTime.now().plusDays(1));
        var delivery=mock(PaperBillingDelivery.class);
        when(delivery.sendReminder(anyMap(),anyInt())).thenAnswer(call->{jdbc.update("UPDATE paper_invoice_document SET paid_at=NOW(6),stage='PAID' WHERE id=?",id);return com.hunt.otziv.client_messages.dto.ClientMessageSendResult.sent("Fixture","reminder-receipt");});
        var reminders=new PaperBillingReminders(store,mock(PaperBillingTargets.class),settings,bank,delivery,parts,new DataSourceTransactionManager(jdbc.getDataSource()));
        reminders.send(id);reminders.tick();
        var row=store.row(id);assertEquals("PAID",row.get("stage"));assertNull(row.get("error_text"));assertNull(row.get("paper_next_reminder_at"));assertEquals("PENDING",row.get("paper_reminder_state"));verify(delivery,times(1)).sendReminder(anyMap(),eq(1));
    }
    @Test void oldBankPurposeContainingOnlyTheOrderNumberIsAppliedOnce() {
        jdbc.update("UPDATE paper_invoice_document SET payment_reference=NULL WHERE id=?",id);
        var legacy=new BankReceipt("customer","40802810000000004287/044525104","legacy-order-payment","7707083893",250000,"Заказ 42");
        settlement.accept(legacy);settlement.accept(legacy);
        verify(finance,times(1)).applyVerifiedPaperBankPayment(8L,250000);
        assertEquals("PAID",store.row(id).get("stage"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt",Integer.class));
    }
    @Test void ambiguousHistoricalOrderNumberNeverChoosesTheNewestInvoice() {
        jdbc.update("UPDATE paper_invoice_document SET payment_reference=NULL,cancelled_at=NOW(6),stage='CANCELLED' WHERE id=?",id);
        String replacement=UUID.randomUUID().toString();
        store.insert(replacement,new Target("ORDER",42L),1,null,250000,"{}","40802810000000004287/044525104","customer","manager","WHATSAPP:1@g.us","test");
        store.bankId(replacement,false,"new-bank-id","WAITING_PAYMENT");
        jdbc.update("UPDATE paper_invoice_document SET payment_reference=NULL WHERE id=?",replacement);
        settlement.accept(new BankReceipt("customer","40802810000000004287/044525104","ambiguous","7707083893",250000,"Заказ №42"));
        verifyNoInteractions(finance);
        assertNull(store.row(replacement).get("paid_at"));
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

    @Test void ordinaryOrderRequiresOneFullTransferAndKeepsShortReceiptsForReview() {
        settlement.accept(receipt("p1",200000,"7707083893"));
        settlement.accept(receipt("p1",200000,"7707083893"));
        settlement.accept(receipt("p2",50000,"7707083893"));
        verifyNoInteractions(finance);
        assertNull(store.row(id).get("paid_at"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt WHERE state='NEEDS_REVIEW'",Integer.class));
    }
    @Test void alreadyRecordedLegacyPartialCanBeCompletedOnce() {
        settlement.accept(receipt("p1",200000,"7707083893"));
        jdbc.update("UPDATE paper_invoice_bank_receipt SET state='PARTIAL' WHERE payment_id='p1'");
        store.jdbc().update("UPDATE paper_invoice_document SET stage='WAITING_PAYMENT' WHERE id=?",id);
        settlement.accept(receipt("p2",50000,"7707083893"));
        settlement.accept(receipt("p2",50000,"7707083893"));
        verify(finance,times(1)).applyVerifiedPaperBankPayment(8L,250000);
    }
    @Test void exactReceiptOnAnotherRouteStopsDocumentCollectionAndQuarantinesDuplicate() {
        assertTrue(store.closeUnpaidDocument("ORDER",42L,id,250000L));
        settlement.accept(receipt("second",250000,"7707083893"));
        settlement.accept(receipt("second",250000,"7707083893"));
        verifyNoInteractions(finance);
        assertNull(store.row(id).get("paid_at"));
        assertEquals("NEEDS_REVIEW",jdbc.queryForObject("SELECT state FROM paper_invoice_bank_receipt",String.class));
    }
    @Test void previousFullReceiptAlsoStopsCollectionOnAnIssuedSubset() {
        jdbc.update("UPDATE paper_invoice_document SET target_kind='COMMON_PART',parent_invoice_id=7 WHERE id=?",id);
        assertTrue(store.closeUnpaidDocument("COMMON_INVOICE",7,id,300000));
        assertEquals("NEEDS_REVIEW",store.row(id).get("stage"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_event WHERE event_key='PAID_BY_PREVIOUS_LINK'",Integer.class));
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
    PaperBillingCancellation cancelWorkflow; PaperBillingBankClient cancelBank; PaperBillingTargets cancelTargets;
    org.springframework.security.core.Authentication cancelActor=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin","unused");
    void cancellationFixture() throws Exception {
        cancelBank=mock(PaperBillingBankClient.class);cancelTargets=mock(PaperBillingTargets.class);
        var settings=new PaperBillingSettings();settings.setEnabled(true);settings.setBankOperationsEnabled(true);
        var authorized=mock(PaperBillingService.class);
        when(authorized.authorized(eq(id),any())).thenAnswer(call->store.row(id));
        when(cancelBank.configured()).thenReturn(true);when(cancelBank.invoicePaymentStatus(any())).thenReturn("payment_waiting");
        doAnswer(call->{jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=42 FOR UPDATE",Long.class);return null;}).when(cancelTargets).lockDelivery(any());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        when(cancelBank.call(eq("/open-banking/v1.0/statements"),eq(org.springframework.http.HttpMethod.POST),any())).thenReturn(json.readTree("{\"Data\":{\"Statement\":{\"statementId\":\"cancel-test\"}}}"));
        when(cancelBank.call(contains("/statements/cancel-test"),eq(org.springframework.http.HttpMethod.GET),isNull())).thenReturn(cancelStatement("Ready",""));
        jdbc.update("UPDATE paper_invoice_document SET bank_act_id='bank-act',message_delivery='SENT',invoice_delivery='SENT',act_delivery='SENT',delivery_completed_at=NOW(6) WHERE id=?",id);
        jdbc.update("INSERT INTO paper_invoice_files(document_id,invoice_pdf,act_pdf) VALUES(?,?,?)",id,new byte[]{1,2},new byte[]{3,4});
        cancelWorkflow=new PaperBillingCancellation(store,authorized,cancelTargets,cancelBank,settings,settlement,mock(PaperBillingDelivery.class),new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    com.fasterxml.jackson.databind.JsonNode cancelStatement(String state,String transactions) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"Data\":{\"Statement\":[{\"accountId\":\"40802810000000004287/044525104\",\"statementId\":\"cancel-test\",\"status\":\""+state+"\",\"Transaction\":["+transactions+"]}]},\"Meta\":{\"totalPages\":1}}");
    }
    void requestCancel() {cancelWorkflow.request(id,new CancelRequest("Ошибка реквизитов",true),cancelActor);}
    void drainCancel() {for(int i=0;i<14 && !Set.of("CANCELLED","CANCEL_REVIEW").contains(store.row(id).get("stage"));i++) cancelWorkflow.process(id);}
    @Test void cancellationDeletesBothDocumentsAndKeepsImmutableHistory() throws Exception {
        cancellationFixture();requestCancel();requestCancel();drainCancel();
        assertEquals("CANCELLED",store.row(id).get("stage"));assertNotNull(store.row(id).get("invoice_deleted_at"));assertNotNull(store.row(id).get("act_deleted_at"));
        assertArrayEquals(new byte[]{1,2},(byte[])store.row(id).get("invoice_pdf"));assertTrue(store.target(new Target("ORDER",42L)).isEmpty());
        assertEquals(id,store.latest(new Target("ORDER",42L)).orElseThrow().get("id"));
        verify(cancelBank,times(1)).deleteDocument(any(),eq(true));verify(cancelBank,times(1)).deleteDocument(any(),eq(false));
        verify(cancelTargets,times(1)).holdAutomation(new Target("ORDER",42L),true);
        verify(cancelTargets,times(1)).cancelled(new Target("ORDER",42L),id,8L);verifyNoInteractions(finance);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_event WHERE event_key='CANCELLED'",Integer.class));
        assertFalse(store.claimMessage(id));assertFalse(store.claimDelivery(id,false));
        store.error(id,"ACT_CREATED","late");store.bankId(id,true,"bank-act","ACT_CREATED");assertEquals("CANCELLED",store.row(id).get("stage"));
        jdbc.update("INSERT INTO payment_links(id) VALUES(9)");String fresh=UUID.randomUUID().toString();
        store.insert(fresh,new Target("ORDER",42L),1,9L,250000,"{}","40802810000000004287/044525104","customer","manager","WHATSAPP:1@g.us","test");
        assertEquals(fresh,store.target(new Target("ORDER",42L)).orElseThrow().get("id"));assertEquals("CANCELLED",store.row(id).get("stage"));
    }
    @Test void paidReceiptWinsBeforeCancellation() throws Exception {
        cancellationFixture();settlement.accept(receipt("paid-first",250000,"7707083893"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,this::requestCancel);
        assertNull(store.row(id).get("cancel_requested_at"));verify(cancelBank,never()).deleteDocument(any(),anyBoolean());
    }
    @Test void cancellationWinsBeforeWebhookAndNeverAppliesThePayment() throws Exception {
        cancellationFixture();requestCancel();settlement.accept(receipt("after-request",250000,"7707083893"));drainCancel();
        assertEquals("CANCEL_REVIEW",store.row(id).get("stage"));assertNull(store.row(id).get("cancelled_at"));verifyNoInteractions(finance);
        assertEquals("NEEDS_REVIEW",jdbc.queryForObject("SELECT state FROM paper_invoice_bank_receipt",String.class));verify(cancelBank,never()).deleteDocument(any(),anyBoolean());
    }
    @Test void simultaneousPaymentAndCancellationHaveOnlyOneFinancialWinner() throws Exception {
        cancellationFixture();
        concurrently(()->settlement.accept(receipt("race-cancel",250000,"7707083893")),()->{try{requestCancel();}catch(org.springframework.web.server.ResponseStatusException alreadyPaid){}});
        if(store.row(id).get("paid_at")!=null) {assertNull(store.row(id).get("cancel_requested_at"));verify(finance).applyVerifiedPaperBankPayment(8L,250000);}
        else {assertEquals("CANCEL_REVIEW",store.row(id).get("stage"));verifyNoInteractions(finance);}
        verify(cancelBank,never()).deleteDocument(any(),anyBoolean());
    }
    @Test void paymentDuringActDeletionStopsInvoiceDeletionButPreservesBankReceipt() throws Exception {
        cancellationFixture();requestCancel();
        doAnswer(call->{settlement.accept(receipt("during-delete",250000,"7707083893"));return null;}).when(cancelBank).deleteDocument(any(),eq(true));
        drainCancel();assertEquals("CANCEL_REVIEW",store.row(id).get("stage"));assertNotNull(store.row(id).get("act_deleted_at"));
        assertNull(store.row(id).get("invoice_deleted_at"));verify(cancelBank,never()).deleteDocument(any(),eq(false));verifyNoInteractions(finance);
    }
    @Test void latePaymentAfterCompletedCancellationIsStoredAndAlertsStaff() throws Exception {
        cancellationFixture();requestCancel();drainCancel();settlement.accept(receipt("too-late",250000,"7707083893"));settlement.accept(receipt("too-late",250000,"7707083893"));
        assertEquals("CANCELLED",store.row(id).get("stage"));assertNull(store.row(id).get("paid_at"));verifyNoInteractions(finance);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_event WHERE event_key='PAYMENT_REVIEW'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt WHERE state='NEEDS_REVIEW'",Integer.class));
    }
    @Test void invoiceDeleteFailureRetainsActReceiptAndRetriesOnlyTheInvoice() throws Exception {
        cancellationFixture();requestCancel();doThrow(new IllegalStateException("timeout")).doNothing().when(cancelBank).deleteDocument(any(),eq(false));
        drainCancel();assertEquals("CANCELLED",store.row(id).get("stage"));verify(cancelBank,times(1)).deleteDocument(any(),eq(true));verify(cancelBank,times(2)).deleteDocument(any(),eq(false));
    }
    @Test void asynchronousStatementIsPolledWithoutIssuingAnotherRequest() throws Exception {
        cancellationFixture();requestCancel();
        when(cancelBank.call(contains("/statements/cancel-test"),eq(org.springframework.http.HttpMethod.GET),isNull())).thenReturn(cancelStatement("Pending",""));
        for(int i=0;i<4;i++) cancelWorkflow.process(id);
        verify(cancelBank,times(1)).call(eq("/open-banking/v1.0/statements"),eq(org.springframework.http.HttpMethod.POST),any());
        verify(cancelBank,never()).deleteDocument(any(),anyBoolean());assertEquals("CANCEL_REQUESTED",store.row(id).get("stage"));
    }
    @Test void partialTransferInFreshStatementBlocksAllDeletion() throws Exception {
        cancellationFixture();requestCancel();
        String transfer="{\"paymentId\":\"partial\",\"creditDebitIndicator\":\"Credit\",\"status\":\"Booked\",\"transactionTypeCode\":\"Платежное поручение\",\"description\":\"Оплата "+store.row(id).get("number")+"\",\"Amount\":{\"amount\":\"1.00\",\"currency\":\"RUB\"},\"DebtorParty\":{\"inn\":\"7707083893\"}}";
        when(cancelBank.call(contains("/statements/cancel-test"),eq(org.springframework.http.HttpMethod.GET),isNull())).thenReturn(cancelStatement("Ready",transfer));
        drainCancel();assertEquals("CANCEL_REVIEW",store.row(id).get("stage"));verify(cancelBank,never()).deleteDocument(any(),anyBoolean());verifyNoInteractions(finance);
    }
    @Test void localFinalizationFailureRollsBackAndCanResume() throws Exception {
        cancellationFixture();requestCancel();doThrow(new IllegalStateException("queue unavailable")).when(cancelTargets).cancelled(any(),anyString(),anyLong());
        drainCancel();assertNull(store.row(id).get("cancelled_at"));assertEquals("FINALIZE",store.row(id).get("cancel_step"));assertTrue(store.target(new Target("ORDER",42L)).isPresent());
        doNothing().when(cancelTargets).cancelled(any(),anyString(),anyLong());drainCancel();assertEquals("CANCELLED",store.row(id).get("stage"));
        verify(cancelBank,times(1)).deleteDocument(any(),eq(true));verify(cancelBank,times(1)).deleteDocument(any(),eq(false));
    }
    @Test void inFlightCreationAndDeliveryCannotBeCancelled() throws Exception {
        cancellationFixture();jdbc.update("UPDATE paper_invoice_document SET stage='ACT_CREATING' WHERE id=?",id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,this::requestCancel);
        jdbc.update("UPDATE paper_invoice_document SET stage='FILES_READY',act_delivery='SENDING' WHERE id=?",id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,this::requestCancel);assertNull(store.row(id).get("cancel_requested_at"));
    }
    @Test void bankPaidStatusBlocksDeletionEvenBeforeWebhookArrives() throws Exception {
        cancellationFixture();when(cancelBank.invoicePaymentStatus(any())).thenReturn("payment_paid");requestCancel();drainCancel();
        assertEquals("CANCEL_REVIEW",store.row(id).get("stage"));verify(cancelBank,never()).deleteDocument(any(),anyBoolean());verifyNoInteractions(finance);
    }
    @Test void eventsHaveSeparateDeduplicationKeysForCancellationAndLatePayment() throws Exception {
        cancellationFixture();requestCancel();drainCancel();settlement.accept(receipt("late-events",250000,"7707083893"));
        var notices=new PaperBillingNoticeStore(jdbc);
        var recipients=List.of(new com.hunt.otziv.u_users.api.PaymentNoticeRecipients.Recipient(1,null,9191L));
        for(var event:notices.unpreparedEvents()) {
            notices.prepareEvent(id,(String)event.get("event_key"),recipients,(String)event.get("body"));
            notices.prepareEvent(id,(String)event.get("event_key"),recipients,"duplicate");
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_staff_notice",Integer.class));assertTrue(notices.unpreparedEvents().isEmpty());
    }
    @Test void pendingUncreatedDocumentCanBeCancelledWithoutAnyBankMutation() throws Exception {
        cancellationFixture();jdbc.update("UPDATE paper_invoice_document SET stage='PENDING',bank_invoice_id=NULL,bank_act_id=NULL,message_delivery='PENDING',invoice_delivery='PENDING',act_delivery='PENDING' WHERE id=?",id);
        requestCancel();drainCancel();assertEquals("CANCELLED",store.row(id).get("stage"));assertEquals("SKIPPED",store.row(id).get("cancel_message_delivery"));
        verify(cancelBank,never()).deleteDocument(any(),anyBoolean());verify(cancelBank,never()).create(any(),any(),anyBoolean());
    }
    @Test void lateReceiptForOldCancelledDocumentBlocksAReplacementPayment() throws Exception {
        cancellationFixture();requestCancel();drainCancel();String fresh=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO payment_links(id) VALUES(9)");
        store.insert(fresh,new Target("ORDER",42L),1,9L,250000,"{}","40802810000000004287/044525104","customer","manager","WHATSAPP:1@g.us","test");
        store.bankId(fresh,false,"new-bank","WAITING_PAYMENT");
        settlement.accept(receipt("old-late",250000,"7707083893"));assertEquals("NEEDS_REVIEW",store.row(fresh).get("stage"));
        settlement.accept(new BankReceipt("customer","40802810000000004287/044525104","new-payment","7707083893",250000,"Оплата "+store.row(fresh).get("number")));
        verifyNoInteractions(finance);assertNull(store.row(fresh).get("paid_at"));assertTrue(store.hasUnresolvedReceipt(new Target("ORDER",42L),fresh));
    }
    @Test void replacementCannotStartBeforeCancellationNoticeIsConfirmed() throws Exception {
        cancellationFixture();requestCancel();drainCancel();var target=new Target("ORDER",42L);
        when(cancelTargets.company(target,cancelActor)).thenReturn(1L);
        var settings=new PaperBillingSettings();settings.setEnabled(true);settings.setBankOperationsEnabled(true);
        var service=new PaperBillingService(store,cancelTargets,settings,cancelBank,new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.issue(target,new IssueRequest(new Payer("company","Тест","7707083893","773601001",""),true),cancelActor));
        verify(cancelBank,never()).supplierAccount();assertTrue(store.target(target).isEmpty());
    }
    @Test void ordinaryHandoffWaitsForCancellationAndNoticeThenCommitsOnce() throws Exception {
        cancellationFixture();cancelWorkflow.request(id,new CancelRequest("Другой способ оплаты",true,true),cancelActor);
        cancelWorkflow.resumeOrdinaryInvoice(id);verify(cancelTargets,never()).resumeOrdinaryInvoice(any());
        drainCancel();assertEquals("PENDING",store.row(id).get("cancel_replacement_state"));
        cancelWorkflow.resumeOrdinaryInvoice(id);verify(cancelTargets,never()).resumeOrdinaryInvoice(any());
        jdbc.update("UPDATE paper_invoice_document SET cancel_message_delivery='UNKNOWN' WHERE id=?",id);
        cancelWorkflow.resumeOrdinaryInvoice(id);verify(cancelTargets,never()).resumeOrdinaryInvoice(any());
        jdbc.update("UPDATE paper_invoice_document SET cancel_message_delivery='SENT' WHERE id=?",id);
        concurrently(()->cancelWorkflow.resumeOrdinaryInvoice(id),()->cancelWorkflow.resumeOrdinaryInvoice(id));
        assertEquals("QUEUED",store.row(id).get("cancel_replacement_state"));
        verify(cancelTargets,times(1)).resumeOrdinaryInvoice(new Target("ORDER",42L));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM paper_invoice_event WHERE event_key='ORDINARY_QUEUED'",Integer.class));
    }
    @Test void cancellationKeepsTheRequestedReplacementAcrossRetries() throws Exception {
        cancellationFixture();
        var mode = com.hunt.otziv.payments.model.InvoicePaymentMode.EMPLOYEE_REQUISITES;
        var request = new CancelRequest("Клиент просит реквизиты", true, true, mode);
        cancelWorkflow.request(id, request, cancelActor);
        cancelWorkflow.request(id, request, cancelActor);
        assertThrows(RuntimeException.class, () -> cancelWorkflow.request(id,
                new CancelRequest("Клиент просит реквизиты", true, true, com.hunt.otziv.payments.model.InvoicePaymentMode.OWNER_TBANK), cancelActor));
        drainCancel();
        jdbc.update("UPDATE paper_invoice_document SET cancel_message_delivery='SENT' WHERE id=?", id);
        cancelWorkflow.resumeOrdinaryInvoice(id);
        cancelWorkflow.resumeOrdinaryInvoice(id);
        verify(cancelTargets).resumeOrdinaryInvoice(new Target("ORDER", 42L), mode);
        assertEquals("QUEUED", store.row(id).get("cancel_replacement_state"));
    }

    @Test void ordinaryQueueFailureRollsBackHandoffAndCanBeRetried() throws Exception {
        cancellationFixture();cancelWorkflow.request(id,new CancelRequest("Другой способ оплаты",true,true),cancelActor);drainCancel();
        jdbc.update("UPDATE paper_invoice_document SET cancel_message_delivery='SENT' WHERE id=?",id);
        doAnswer(call->{jdbc.update("UPDATE orders SET paper_invoice_automation_hold=0 WHERE order_id=42");throw new IllegalStateException("queue unavailable");}).when(cancelTargets).resumeOrdinaryInvoice(any());
        jdbc.update("UPDATE orders SET paper_invoice_automation_hold=1 WHERE order_id=42");
        assertThrows(IllegalStateException.class,()->cancelWorkflow.resumeOrdinaryInvoice(id));
        assertEquals("PENDING",store.row(id).get("cancel_replacement_state"));assertEquals(1,jdbc.queryForObject("SELECT paper_invoice_automation_hold FROM orders WHERE order_id=42",Integer.class));
        doNothing().when(cancelTargets).resumeOrdinaryInvoice(any());cancelWorkflow.resumeOrdinaryInvoice(id);
        assertEquals("QUEUED",store.row(id).get("cancel_replacement_state"));verify(cancelBank,times(2)).deleteDocument(any(),anyBoolean());
    }
    @Test void transferAfterCancellationBlocksOrdinaryHandoff() throws Exception {
        cancellationFixture();cancelWorkflow.request(id,new CancelRequest("Другой способ оплаты",true,true),cancelActor);drainCancel();
        jdbc.update("UPDATE paper_invoice_document SET cancel_message_delivery='SENT' WHERE id=?",id);
        settlement.accept(receipt("late-before-handoff",250000,"7707083893"));cancelWorkflow.resumeOrdinaryInvoice(id);
        assertEquals("REVIEW",store.row(id).get("cancel_replacement_state"));verify(cancelTargets,never()).resumeOrdinaryInvoice(any());verifyNoInteractions(finance);
    }
}
