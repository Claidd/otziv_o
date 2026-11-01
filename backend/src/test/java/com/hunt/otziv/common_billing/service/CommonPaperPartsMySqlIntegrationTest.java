package com.hunt.otziv.common_billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.common_billing.api.*;
import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.p_products.api.PaperInvoiceOrderData;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.client_messages.api.PaperInvoiceAutomation;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Real MySQL reservations/rollback/locking; financial application is separately exercised in the Docker UI scenario. */
@Testcontainers
class CommonPaperPartsMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
        .withDatabaseName("parts").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc;TransactionTemplate tx;BankPaperInvoicePartsService service;CommonInvoiceSettlementService financial;
    PaperInvoiceOrderData orders;CommonInvoiceDeliveryService delivery;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");for(String table:jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema=DATABASE()",String.class))jdbc.execute("DROP TABLE "+table);jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.execute("CREATE TABLE companies(company_id BIGINT PRIMARY KEY,company_title VARCHAR(100))");jdbc.update("INSERT INTO companies VALUES(1,'A'),(2,'B'),(3,'C')");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_company BIGINT)");jdbc.update("INSERT INTO orders VALUES(1,1),(2,1),(3,1),(4,2),(5,2),(6,3)");
        jdbc.execute("CREATE TABLE common_invoices(invoice_id BIGINT PRIMARY KEY,status VARCHAR(24),paper_bank_document_id CHAR(36),paper_invoice_review_hold BOOLEAN DEFAULT FALSE,paper_invoice_automation_hold BOOLEAN DEFAULT FALSE,paid_kopecks BIGINT DEFAULT 0)");jdbc.update("INSERT INTO common_invoices(invoice_id,status) VALUES(8,'READY')");
        jdbc.execute("CREATE TABLE common_invoice_orders(order_id BIGINT PRIMARY KEY,invoice_id BIGINT,amount_kopecks BIGINT,ready BOOLEAN,paid BOOLEAN DEFAULT FALSE,active_membership BOOLEAN DEFAULT TRUE)");jdbc.update("INSERT INTO common_invoice_orders(order_id,invoice_id,amount_kopecks,ready) SELECT order_id,8,10000,TRUE FROM orders");
        jdbc.execute("CREATE TABLE paper_invoice_document(id CHAR(36),created_at DATETIME(6))");new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_356__common_invoice_paper_parts.sql")).execute(ds);
        var invoices=mock(CommonInvoiceRepository.class);when(invoices.findById(8L)).thenAnswer(c->Optional.of(invoice()));
        when(invoices.save(any())).thenAnswer(c->{CommonInvoice i=c.getArgument(0);jdbc.update("UPDATE common_invoices SET status=?,paper_bank_document_id=?,paper_invoice_automation_hold=?,paid_kopecks=? WHERE invoice_id=8",i.getStatus().name(),i.getPaperBankDocumentId(),i.isPaperInvoiceAutomationHold(),i.getPaidKopecks());return i;});
        delivery=mock(CommonInvoiceDeliveryService.class);when(delivery.lockedInvoiceAfterStandalonePaymentPrelude(8L)).thenAnswer(c->{jdbc.queryForList("SELECT order_id FROM orders ORDER BY order_id FOR UPDATE");jdbc.queryForList("SELECT invoice_id FROM common_invoices WHERE invoice_id=8 FOR UPDATE");return new CommonInvoiceInitializationService.LockedInvoicePaymentPrelude(invoice(),Map.of());});
        var items=mock(CommonInvoiceOrderRepository.class);when(items.findByInvoiceIdWithOrders(8L)).thenAnswer(c->rows());
        when(items.saveAll(any())).thenAnswer(c->{Iterable<CommonInvoiceOrder> values=c.getArgument(0);for(var i:values)jdbc.update("UPDATE common_invoice_orders SET paid=? WHERE order_id=?",i.isPaid(),i.getOrder().getId());return values;});
        financial=mock(CommonInvoiceSettlementService.class);
        doAnswer(c->{CommonInvoice i=c.getArgument(0);i.setPaidKopecks(rows().stream().filter(CommonInvoiceOrder::isPaid).mapToLong(CommonInvoiceOrder::getAmountKopecks).sum());return null;}).when(financial).recalculateInvoice(any(),anyList());
        doAnswer(c->{CommonInvoice i=c.getArgument(0);i.setStatus(CommonInvoiceStatus.PAID);invoices.save(i);return null;}).when(financial).closePaidInvoice(any(),anyList());
        var legacy=mock(BankPaperInvoiceAdapter.class);when(legacy.read(8)).thenReturn(new BankPaperInvoices.Snapshot(1,60000));orders=mock(PaperInvoiceOrderData.class);
        service=new BankPaperInvoicePartsService(jdbc,delivery,mock(CommonInvoicePaymentRouteWorkflow.class),invoices,items,mock(CommonInvoiceManualPaymentWorkflow.class),financial,legacy,orders,mock(PaperInvoiceAutomation.class),mock(com.hunt.otziv.review_recovery.api.ReviewRecoveryMessageHold.class));
    }
    CommonInvoice invoice(){var row=jdbc.queryForMap("SELECT * FROM common_invoices WHERE invoice_id=8");var i=new CommonInvoice();i.setId(8L);i.setInvoicePaymentMode(InvoicePaymentMode.OWNER_PAPER_INVOICE);i.setStatus(CommonInvoiceStatus.valueOf((String)row.get("status")));i.setAmountKopecks(60000);i.setPaidKopecks(((Number)row.get("paid_kopecks")).longValue());i.setPaperBankDocumentId((String)row.get("paper_bank_document_id"));i.setPaperInvoiceReviewHold(Boolean.TRUE.equals(row.get("paper_invoice_review_hold")));return i;}
    List<CommonInvoiceOrder> rows(){return jdbc.queryForList("SELECT * FROM common_invoice_orders").stream().map(r->{var i=new CommonInvoiceOrder();var o=new Order();o.setId(((Number)r.get("order_id")).longValue());i.setOrder(o);i.setReady(Boolean.TRUE.equals(r.get("ready")));i.setPaid(Boolean.TRUE.equals(r.get("paid")));i.setActiveMembership(true);i.setAmountKopecks(((Number)r.get("amount_kopecks")).longValue());return i;}).toList();}
    <T>T write(Supplier<T> action){return tx.execute(s->action.get());}
    long part(String key,Long...ids){return write(()->service.create(8,List.of(ids),key));}
    @Test void sixOrdersCanBeReservedAsThreeTwoAndOneWithoutOverlap() {
        long a=part("request-a",1L,2L,3L),b=part("request-b",4L,5L),c=part("request-c",6L);
        assertEquals(30000,service.read(a).amountKopecks());assertEquals(20000,service.read(b).amountKopecks());assertEquals(10000,service.read(c).amountKopecks());
        assertEquals(a,part("request-a",3L,2L,1L));assertThrows(RuntimeException.class,()->part("request-d",3L,4L));assertThrows(RuntimeException.class,()->part("request-a",4L));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_paper_parts",Integer.class));
    }
    @Test void invalidSelectionAndOuterFailureLeaveNoReservations() {
        jdbc.update("UPDATE common_invoice_orders SET ready=FALSE WHERE order_id=2");assertThrows(RuntimeException.class,()->part("notready",1L,2L));assertThrows(RuntimeException.class,()->part("duplicate",1L,1L));
        assertThrows(RuntimeException.class,()->write(()->{service.create(8,List.of(1L),"rollback");throw new IllegalStateException("delivery validation failed");}));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_paper_parts",Integer.class));
    }
    @Test void compositionDriftPreventsAnyFinancialApplication() {
        long a=part("request-a",1L,2L);jdbc.update("UPDATE common_invoice_orders SET amount_kopecks=20000 WHERE order_id=2");assertThrows(RuntimeException.class,()->write(()->{service.settle(a,20000,"transfer");return null;}));verifyNoInteractions(financial);
    }
    @Test void verifiedBankCompositionDoesNotDependOnManagerBrowserAuthentication() {
        long a=part("request-a",1L,2L);
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"No browser user")).when(delivery).ensureCommonInvoiceVisibleForCurrentUser(any());
        assertThrows(RuntimeException.class,()->service.positions(8));
        assertEquals(64,service.composition(a).length());
        assertDoesNotThrow(()->write(()->{service.settle(a,20000,"signed-bank-receipt");return null;}));
    }
    @Test void eachPaymentClosesOnlyItsOrdersAndParentClosesAfterAllParts() throws Exception {
        long a=part("request-a",1L,2L,3L),b=part("request-b",4L,5L),c=part("request-c",6L);
        write(()->{service.settle(a,30000,"first");return null;});assertEquals("PARTIALLY_PAID",invoice().getStatus().name());assertEquals(30000,invoice().getPaidKopecks());verify(financial,never()).closePaidInvoice(any(),anyList());verify(financial,times(3)).closeBankPaperPositionAsPaid(any());
        write(()->{service.settle(b,20000,"second");return null;});assertEquals(50000,invoice().getPaidKopecks());verify(financial,never()).closePaidInvoice(any(),anyList());
        write(()->{service.settle(c,10000,"last");return null;});assertEquals(CommonInvoiceStatus.PAID,invoice().getStatus());verify(financial,times(1)).closePaidInvoice(any(),argThat(list->list.size()==6));
        assertThrows(RuntimeException.class,()->write(()->{service.settle(a,30000,"duplicate");return null;}));
    }
    @Test void cancellationReleasesOnlyItsSubsetAndNeverPaidSiblings() {
        long a=part("request-a",1L,2L),b=part("request-b",3L,4L);write(()->{service.bind(a,"doc-a");service.bind(b,"doc-b");return null;});
        write(()->{service.cancelled(a,"doc-a");return null;});assertEquals("doc-b",invoice().getPaperBankDocumentId());assertNull(service.positions(8).getFirst().documentId());assertThrows(RuntimeException.class,()->part("overlap",3L));assertTrue(part("replacement",1L,2L)>b);
    }
    @Test void concurrentOverlappingSelectionsReserveOrdersOnlyOnce() throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);var successes=new java.util.concurrent.atomic.AtomicInteger();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<2;i++){String key="request-"+i;futures.add(pool.submit(()->{try{start.await();part(key,1L,2L);successes.incrementAndGet();}catch(org.springframework.web.server.ResponseStatusException expected){}catch(InterruptedException e){throw new RuntimeException(e);}}));}start.countDown();for(var f:futures)f.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1,successes.get());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_paper_part_orders",Integer.class));
    }
}
