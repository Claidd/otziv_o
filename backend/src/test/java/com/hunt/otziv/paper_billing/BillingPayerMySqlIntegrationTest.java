package com.hunt.otziv.paper_billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import com.hunt.otziv.c_companies.api.PaperInvoiceCompanyData;
import com.hunt.otziv.manager.api.PaymentReviewOrderAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class BillingPayerMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
        .withDatabaseName("payers").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc;TransactionTemplate tx;BillingPayerService service;PaymentReviewOrderAccess access;
    final TestingAuthenticationToken actor=new TestingAuthenticationToken("manager","","ROLE_MANAGER");
    final Payer payer=new Payer("company","Первый плательщик","7707083893","773601001","Москва");
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("DROP TABLE IF EXISTS company_billing_payer_choice");
        for(String table:jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema=DATABASE()",String.class))jdbc.execute("DROP TABLE "+table);
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.execute("CREATE TABLE companies(company_id BIGINT PRIMARY KEY,paper_billing_automatic BOOLEAN DEFAULT FALSE,paper_billing_payer_choice_id CHAR(36))");
        jdbc.update("INSERT INTO companies(company_id) VALUES(1),(2),(3)");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_company BIGINT)");
        jdbc.execute("CREATE TABLE common_invoice_orders(invoice_id BIGINT,order_id BIGINT)");
        jdbc.execute("CREATE TABLE company_billing_payer(company_id BIGINT PRIMARY KEY,payer_json JSON,updated_by VARCHAR(150),updated_at DATETIME(6))");
        jdbc.execute("CREATE TABLE paper_invoice_document(id CHAR(36),target_kind VARCHAR(24),target_id BIGINT,payer_json TEXT,bank_invoice_id VARCHAR(36),cancelled_at DATETIME(6),created_at DATETIME(6))");
        jdbc.update("INSERT INTO company_billing_payer VALUES(3,'{}','legacy',NOW(6))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_355__reusable_billing_payers.sql")).execute(ds);
        access=mock(PaymentReviewOrderAccess.class);var companies=mock(PaperInvoiceCompanyData.class);
        when(companies.read(anyLong())).thenAnswer(c->jdbc.queryForMap("SELECT * FROM companies WHERE company_id=?",(Object)c.getArgument(0)));
        doAnswer(c->{jdbc.update("UPDATE companies SET paper_billing_automatic=?,paper_billing_payer_choice_id=? WHERE company_id=?",c.getArgument(1),c.getArgument(2),c.getArgument(0));return null;}).when(companies).automatic(anyLong(),anyBoolean(),nullable(String.class));
        service=new BillingPayerService(jdbc,new ObjectMapper(),access,companies);
    }
    <T>T write(Supplier<T> action){return tx.execute(s->action.get());}
    BillingPayerService.Choice add(long company,Payer p){return write(()->service.save(company,null,new BillingPayerService.Save(p,null,false),actor));}
    @Test void optionalEmptyLegacyRequisitesDoNotPreventMigrationOrCompanyCreation() {
        assertTrue(service.list(3,actor).isEmpty());jdbc.update("INSERT INTO companies(company_id) VALUES(4)");assertFalse(service.preference(4,actor).automatic());
    }
    @Test void samePayerCanBeReusedAcrossCompaniesButEditingIsScopedAndHistoryIsImmutable() {
        var a=add(1,payer);var b=add(2,payer);assertEquals(a.profileId(),b.profileId());
        jdbc.update("INSERT INTO paper_invoice_document VALUES('issued','ORDER',42,?,'bank',NULL,NOW(6))",new ObjectMapper().valueToTree(payer).toString());
        var changed=new Payer("company","Новое наименование",payer.inn(),payer.kpp(),"Новый адрес");
        var edited=write(()->service.save(1,a.id(),new BillingPayerService.Save(changed,a.version(),true),actor));
        assertNotEquals(a.profileId(),edited.profileId());assertEquals(payer,service.list(2,actor).getFirst().payer());
        assertTrue(jdbc.queryForObject("SELECT payer_json FROM paper_invoice_document WHERE id='issued'",String.class).contains("Первый плательщик"));
    }
    @Test void multiplePayersRemainAvailableAndDefaultIsIndependentOfOtherCompany() {
        var first=add(1,payer);var second=add(1,new Payer("ip","ИП Тест","380124742639","",""));
        assertEquals(2,service.list(1,actor).size());assertTrue(service.list(1,actor).stream().filter(c->c.id().equals(first.id())).findFirst().orElseThrow().primary());
        var next=write(()->service.save(1,second.id(),new BillingPayerService.Save(second.payer(),second.version(),true),actor));
        assertTrue(next.primary());assertEquals(1,service.list(1,actor).stream().filter(BillingPayerService.Choice::primary).count());
    }
    @Test void staleEditsAndDeletesCannotOverwriteNewRequisites() {
        var a=add(1,payer);write(()->service.save(1,a.id(),new BillingPayerService.Save(new Payer("company","Изменено",payer.inn(),payer.kpp(),""),a.version(),true),actor));
        assertThrows(RuntimeException.class,()->write(()->service.save(1,a.id(),new BillingPayerService.Save(payer,a.version(),true),actor)));
        assertThrows(RuntimeException.class,()->write(()->{service.delete(1,a.id(),a.version(),actor);return null;}));assertEquals("Изменено",service.list(1,actor).getFirst().payer().name());
    }
    @Test void deletedAutomaticPayerStopsIssuanceUntilExplicitReplacementIsChosen() {
        var first=add(1,payer);var alternative=add(1,new Payer("ip","ИП Тест","380124742639","",""));
        write(()->{service.preference(1,new BillingPayerService.Preference(true,first.id()),actor);return null;});
        write(()->{service.delete(1,first.id(),first.version(),actor);return null;});
        assertThrows(RuntimeException.class,()->service.requireAutomatic(1,first.id(),payer));
        assertFalse(service.historyAllowed(List.of(1L),payer));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM billing_payer_profile",Integer.class));
        write(()->{service.preference(1,new BillingPayerService.Preference(true,alternative.id()),actor);return null;});
        assertDoesNotThrow(()->service.requireAutomatic(1,first.id(),alternative.payer()));
    }
    @Test void companyScopeAndSavedVersionAreCheckedAgainBeforeIssue() {
        var a=add(1,payer);
        assertThrows(RuntimeException.class,()->service.validateSelection(List.of(2L),a.id(),a.version(),payer));
        assertThrows(RuntimeException.class,()->service.validateSelection(List.of(1L),a.id(),a.version()+1,payer));
        assertDoesNotThrow(()->service.validateSelection(List.of(1L),a.id(),a.version(),payer));
    }
    @Test void deniedCompanyCannotReadOrWritePayers() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(access).requireCompanyAccess(2L,actor);
        assertThrows(RuntimeException.class,()->service.list(2,actor));assertThrows(RuntimeException.class,()->add(2,payer));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM company_billing_payer_choice",Integer.class));
    }
    @Test void concurrentFirstSaveCreatesOneCompanyChoiceAndOneDefault() throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return add(1,payer);});var b=pool.submit(()->{start.await();return add(1,payer);});start.countDown();
            assertEquals(a.get(10,java.util.concurrent.TimeUnit.SECONDS).id(),b.get(10,java.util.concurrent.TimeUnit.SECONDS).id());
        }
        assertEquals(1,service.list(1,actor).size());assertTrue(service.list(1,actor).getFirst().primary());
    }
}
