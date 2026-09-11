package com.hunt.otziv.performance;

import com.hunt.otziv.analytics.service.AnalyticsSalarySourceService;
import com.hunt.otziv.p_products.worker_access.service.WorkerIpClassificationStore;
import com.hunt.otziv.p_products.worker_access.service.WorkerIpIntelligenceClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class InteractiveReadModelsMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(FinancialScenarioBenchmark.MYSQL_IMAGE)
            .withDatabaseName("interactive_models").withUsername("root").withPassword("local-test-only");
    static JdbcTemplate jdbc;
    static AnalyticsSalarySourceService salaries;
    static WorkerIpClassificationStore classifications;
    static TransactionTemplate tx;

    @BeforeAll static void setup() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        for (String table : List.of("zp", "archive_zp")) jdbc.execute("CREATE TABLE " + table
                + " (zp_id BIGINT PRIMARY KEY,zp_user BIGINT,zp_active BOOLEAN,zp_date DATE,zp_sum DECIMAL(20,2),zp_amount BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE contractor_payment_profiles(id BIGINT PRIMARY KEY,user_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE contractor_reward_ledger(id BIGINT PRIMARY KEY,profile_id BIGINT,source_zp_id BIGINT,active BOOLEAN,occurred_on DATE,amount_kopecks BIGINT,work_units BIGINT) ENGINE=InnoDB");
        String legacy;
        try (var input = new ClassPathResource("db/migration/V1_10_269__archive_aware_analytics_financial_sources.sql").getInputStream()) {
            legacy = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = legacy.indexOf("CREATE OR REPLACE VIEW analytics_salary_source");
        jdbc.execute(legacy.substring(start, legacy.indexOf(';', start)));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_315__worker_ip_intelligence_cache.sql"),
                new ClassPathResource("db/migration/V1_10_316__indexed_daily_salary_reads.sql")).execute(ds);
        salaries = new AnalyticsSalarySourceService(new NamedParameterJdbcTemplate(ds));
        classifications = new WorkerIpClassificationStore(jdbc);
    }

    @BeforeEach void seed() {
        for (String table : List.of("zp", "archive_zp", "contractor_reward_ledger", "contractor_payment_profiles", "worker_ip_intelligence_cache"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO contractor_payment_profiles VALUES (11,1),(12,1),(21,2)");
        jdbc.update("""
                INSERT INTO zp VALUES
                (1,1,1,'2026-09-01',100,2),(2,1,1,'2026-09-01',900,9),
                (3,1,1,'2026-09-02',800,8),(5,1,0,'2026-09-02',700,7),(6,NULL,1,'2026-09-02',60,1)
                """);
        jdbc.update("""
                INSERT INTO archive_zp VALUES
                (1,1,1,'2026-09-01',100,2),(2,1,1,'2026-09-01',900,9),
                (3,1,1,'2026-09-02',800,8),(4,1,1,'2026-09-02',40,4),(5,1,1,'2026-09-02',700,7)
                """);
        jdbc.update("""
                INSERT INTO contractor_reward_ledger VALUES
                (1,11,2,1,'2026-09-01',5050,1),(2,12,2,1,'2026-09-01',5025,2),
                (3,21,2,1,'2026-09-01',15050,3),(4,11,3,0,'2026-09-02',80000,8),
                (5,21,6,1,'2026-09-02',6000,1)
                """);
    }

    @Test void dailyReadMatchesCanonicalAttributionIncludingArchiveRestorationAndSplitRewards() {
        LocalDate from = LocalDate.of(2026,9,1), to = from.plusDays(3);
        var actual = salaries.dailyForUsers(List.of(1L,2L), from, to);
        var expected = jdbc.query("""
                SELECT metric_date,user_id,COALESCE(SUM(salary_sum),0),COUNT(DISTINCT source_zp_id),COALESCE(SUM(salary_review_count),0)
                FROM analytics_salary_source WHERE user_id IN (1,2) AND metric_date BETWEEN ? AND ?
                GROUP BY metric_date,user_id ORDER BY metric_date,user_id
                """, (row,index) -> new AnalyticsSalarySourceService.DailySalary(row.getDate(1).toLocalDate(), row.getLong(2),
                row.getBigDecimal(3), row.getLong(4), row.getLong(5)), from, to);
        assertThat(actual).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(expected);
        assertThat(actual).hasSize(4);
        assertThat(actual.getFirst().salarySum()).isEqualByComparingTo("200.75");
        assertThat(actual.getFirst().salaryEntryCount()).isEqualTo(2);
        assertThat(salaries.dailyForUsers(List.of(1L), from, to)).allMatch(row -> row.userId() == 1);
        assertThat(salaries.dailyForUsers(List.of(999L), from, to)).isEmpty();
    }

    @Test void sourceChangeIsVisibleImmediatelyAndRollbackRestoresTheSameTotals() {
        LocalDate date = LocalDate.of(2026,9,2);
        var before = salaries.dailyForUsers(List.of(1L), date, date);
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE archive_zp SET zp_active=0 WHERE zp_id=4");
            assertThat(salaries.dailyForUsers(List.of(1L), date, date)).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(salaries.dailyForUsers(List.of(1L), date, date)).isEqualTo(before);
    }

    @Test void classificationRetainsOriginalExpiryAndLateWriterCannotReplaceNewerRiskEvidence() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String key = "a".repeat(64);
        var risk = new WorkerIpClassificationStore.Entry(new WorkerIpIntelligenceClient.IpIntelligence(true,true,true,"test","ipquery"), now, now.plusSeconds(3600));
        classifications.save(key,risk);
        var old = new WorkerIpClassificationStore.Entry(new WorkerIpIntelligenceClient.IpIntelligence(true,true,false,"old","ipquery"), now.minusSeconds(30), now.plusSeconds(7200));
        classifications.save(key,old);
        var saved = classifications.find(key,Duration.ofHours(24)).orElseThrow();
        assertThat(saved).isEqualTo(risk);
        assertThat(classifications.find("b".repeat(64),Duration.ofHours(24))).isEmpty();
        jdbc.update("UPDATE worker_ip_intelligence_cache SET expires_epoch_ms=?",now.minusSeconds(1).toEpochMilli());
        assertThat(classifications.find(key,Duration.ofHours(24))).isEmpty();
        classifications.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_ip_intelligence_cache",Long.class)).isZero();
    }
}
