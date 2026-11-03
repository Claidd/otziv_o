package com.hunt.otziv.paper_billing;

import com.hunt.otziv.common_billing.api.BankPaperInvoiceParts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

/** No outer test transaction or open view: manager visibility must read real lazy account relations. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.jpa.open-in-view=false","telegram.bot.sending-enabled=false","telegram.bot.registration-enabled=false",
    "max.bot.webhook-auto-register-enabled=false","max.bot.long-polling-enabled=false"})
@ActiveProfiles("test") @Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PaperBillingManagerContextMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("otziv").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withCommand("--restrict-fk-on-non-standard-key=OFF");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);
        r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.datasource.driver-class-name",MYSQL::getDriverClassName);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired BankPaperInvoiceParts parts;
    @Autowired PaperBillingService billing;
    @AfterEach void clearAuthentication() {SecurityContextHolder.clearContext();}

    @Test void assignedManagerCanLoadPaperHistoryAndContextWithoutDetachedProxyFailures() {
        long id=900001L;
        jdbc.update("INSERT INTO users(id,username,active) VALUES(?,'paper_manager_fixture',1)",id);
        jdbc.update("INSERT INTO managers(manager_id,user_id) VALUES(?,?)",id,id);
        jdbc.update("INSERT INTO companies(company_id,company_title,company_user,company_manager) VALUES(?,'Paper context fixture',?,?)",id,id,id);
        jdbc.update("INSERT INTO common_billing_accounts(account_id,account_name,manager_id,invoice_company_id) VALUES(?,'Paper context fixture',?,?)",id,id,id);
        jdbc.update("INSERT INTO common_invoices(invoice_id,account_id,token,title,status,amount_kopecks,invoice_payment_mode) VALUES(?,?,'paper-manager-fixture-token','Paper context fixture','INVOICED',10000,'OWNER_PAPER_INVOICE')",id,id);
        var auth=new UsernamePasswordAuthenticationToken("paper_manager_fixture","unused",List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        assertThat(parts.historyInvoiceIds(id)).containsExactly(id);
        assertThat(parts.partIds(id)).isEmpty();
        var context=billing.context(new PaperBillingModels.Target("COMMON_INVOICE",id),auth);
        assertThat(context.companyId()).isEqualTo(id);
        assertThat(context.amountKopecks()).isEqualTo(10000);
        assertThat(context.documents()).isEmpty();
    }
}
