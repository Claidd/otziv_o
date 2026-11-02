package com.hunt.otziv.whatsapp.service;

import static org.assertj.core.api.Assertions.*;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Executes the production conditional update against MySQL, including case-sensitive invite races. */
@Testcontainers
class WhatsAppDeliveryRouteMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("route_recovery").withUsername("root").withPassword("root");
    private static JdbcTemplate jdbc;
    private static String update;
    private static final String INVITE = "https://chat.whatsapp.com/AbCdEf1234567890";

    @BeforeAll static void start() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("CREATE TABLE company_status(company_status_id BIGINT PRIMARY KEY, status_title VARCHAR(100)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE companies(company_id BIGINT PRIMARY KEY, company_status BIGINT, company_url_chat VARCHAR(500) COLLATE utf8mb4_0900_ai_ci, company_group_id VARCHAR(160), row_version BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB");
        try (var migration = new ClassPathResource("db/migration/V1_10_361__verify_changed_whatsapp_invites.sql").getInputStream()) {
            jdbc.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
        }
        jdbc.update("INSERT INTO company_status VALUES (1, 'Работа'), (2, 'Бан')");
        update = CompanyRepository.class.getMethod("restoreMissingWhatsAppGroup", Long.class, String.class, String.class).getAnnotation(Query.class).value();
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM companies");
        jdbc.update("INSERT INTO companies VALUES (1,1,?,NULL,0,1)", INVITE);
    }
    @Test void commitsTheRecoveredRouteOnce() {
        assertThat(repair(INVITE)).isEqualTo(1);
        assertThat(repair(INVITE)).isZero();
        assertThat(group()).isEqualTo("123456789@g.us");
        assertThat(jdbc.queryForObject("SELECT company_whatsapp_invite_pending FROM companies WHERE company_id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT row_version FROM companies WHERE company_id=1", Long.class)).isEqualTo(1L);
    }
    @Test void concurrentManagerBindingCannotBeOverwritten() {
        jdbc.update("UPDATE companies SET company_group_id='987654321@g.us' WHERE company_id=1");
        assertThat(repair(INVITE)).isZero();
        assertThat(group()).isEqualTo("987654321@g.us");
    }
    @Test void inviteEditDifferingOnlyByCaseInvalidatesTheResolvedRoute() {
        jdbc.update("UPDATE companies SET company_url_chat=? WHERE company_id=1", INVITE.replace("AbCd", "abcd"));
        assertThat(repair(INVITE)).isZero();
        assertThat(group()).isNull();
    }
    @Test void companyBannedDuringInviteLookupCannotBeRebound() {
        jdbc.update("UPDATE companies SET company_status=2 WHERE company_id=1");
        assertThat(repair(INVITE)).isZero();
        assertThat(group()).isNull();
    }
    @Test void anEmptyLegacyGroupCanBeRepaired() {
        jdbc.update("UPDATE companies SET company_group_id='  ' WHERE company_id=1");
        assertThat(repair(INVITE)).isEqualTo(1);
        assertThat(group()).isEqualTo("123456789@g.us");
    }
    private int repair(String invite) {
        return new NamedParameterJdbcTemplate(jdbc).update(update, Map.of("companyId",1L,"urlChat",invite,"groupId","123456789@g.us"));
    }
    private String group() { return jdbc.queryForObject("SELECT company_group_id FROM companies WHERE company_id=1", String.class); }
}
