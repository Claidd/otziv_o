package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.c_companies.repository.CompanyOrganizationIdentityRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class SharedTwoGisHistoryMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("shared_history").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private TransactionTemplate tx;
    private CompanyOrganizationIdentityRepository identities;
    private ReviewBotCurrentUsageRepository current;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        for (String table : new String[]{"filial", "two_gis_organizations", "two_gis_url_identities", "company_two_gis_identities",
                "reviews", "orders", "order_details", "archive_reviews", "archive_orders", "archive_order_details",
                "bad_review_tasks", "review_recovery_tasks", "bots"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE filial (filial_id BIGINT PRIMARY KEY, company_id BIGINT, filial_url VARCHAR(2048)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE orders (order_id BIGINT PRIMARY KEY, order_company BIGINT)");
        jdbc.execute("CREATE TABLE order_details (order_detail_id BIGINT PRIMARY KEY, order_detail_order BIGINT)");
        jdbc.execute("CREATE TABLE reviews (review_id BIGINT PRIMARY KEY, review_filial BIGINT, review_order_details BIGINT, review_bot BIGINT, review_publish BOOLEAN, KEY(review_bot)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE archive_orders (order_id BIGINT PRIMARY KEY, order_company BIGINT, restored_at TIMESTAMP NULL)");
        jdbc.execute("CREATE TABLE archive_order_details LIKE order_details");
        jdbc.execute("CREATE TABLE archive_reviews LIKE reviews");
        jdbc.execute("CREATE TABLE bots (bot_id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE bad_review_tasks (bad_review_task_id BIGINT PRIMARY KEY, bad_review_task_order BIGINT, bad_review_task_bot BIGINT, bad_review_task_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE review_recovery_tasks (review_recovery_task_id BIGINT PRIMARY KEY, review_recovery_task_order BIGINT, review_recovery_task_bot BIGINT, review_recovery_task_status VARCHAR(32), review_recovery_task_archive_company_id BIGINT)");
        jdbc.update("INSERT INTO filial VALUES (2905,2132,'https://go.2gis.com/h8eeD'), (3416,2458,'https://2gis.ru/magnitogorsk/geo/3659702978432070/'), (3485,2514,'https://2gis.ru/magnitogorsk/firm/3659702978432070?m=1'), (4000,3000,'https://2gis.ru/magnitogorsk/firm/999999'), (4001,3001,'https://2gis.ru.evil.test/firm/999999'), (4002,3002,'https://2gis.ru/magnitogorsk/inside/555555/firm/888888/tab/reviews')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_285__shared_two_gis_account_history.sql")).execute(source);
        identities = new CompanyOrganizationIdentityRepository(jdbc);
        current = new ReviewBotCurrentUsageRepository(named, mock(EntityManager.class));
    }

    @Test
    void migrationLinksVerifiedVariantsAndPreservesCompanyWideTransitiveScope() {
        assertThat(identities.relatedCompanyIds(2514L)).containsExactlyInAnyOrder(2132L,2458L,2514L);
        assertThat(identities.relatedCompanyIds(3000L)).containsExactly(3000L);
        assertThat(jdbc.queryForObject("SELECT two_gis_organization_id FROM filial WHERE filial_id=4001", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT two_gis_organization_id FROM filial WHERE filial_id=4002", String.class)).isEqualTo("888888");
        identities.rememberCompany(3000L, "3659702978432070");
        identities.rememberCompany(3003L, "999999");
        assertThat(identities.relatedCompanyIds(2514L)).containsExactlyInAnyOrder(2132L,2458L,2514L,3000L,3003L);
    }

    @Test
    void liveAndArchiveRepositoryHistoryIncludesOrderFallbackButNotRestoredArchive() throws Exception {
        jdbc.update("INSERT INTO reviews VALUES (1,2905,NULL,101,TRUE)");
        jdbc.update("INSERT INTO archive_orders VALUES (10,2132,NULL),(11,2132,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO archive_order_details VALUES (10,10),(11,11)");
        jdbc.update("INSERT INTO archive_reviews VALUES (2,2905,10,102,TRUE),(3,NULL,10,103,TRUE),(4,2905,11,104,TRUE)");
        String sql = ReviewRepository.class.getMethod("findUsedBotIdsByCompanyId", Long.class).getAnnotation(Query.class).value();
        var used = named.query(sql, java.util.Map.of("companyId",2132L), (rs,row) -> rs.getLong(1));
        assertThat(used).containsExactlyInAnyOrder(101L,102L,103L);
        tx.executeWithoutResult(status -> {
            assertThat(isUsed(102L)).isTrue();
            assertThat(isUsed(103L)).isTrue();
            assertThat(isUsed(104L)).isFalse();
        });
    }

    @Test
    void currentReadCoversCompletedTasksAndGlobalQueuesWithSelfExclusions() {
        jdbc.update("INSERT INTO orders VALUES (10,2132),(11,3000)");
        jdbc.update("INSERT INTO bad_review_tasks VALUES (1,10,101,'DONE'),(2,11,102,'NEW'),(3,10,103,'CANCELED')");
        jdbc.update("INSERT INTO review_recovery_tasks VALUES (1,NULL,104,'DONE',2132),(2,11,105,'PLANNED',NULL),(3,10,106,'CANCELED',NULL)");
        tx.executeWithoutResult(status -> {
            for (long bot : new long[]{101,102,104,105}) assertThat(isUsed(bot)).isTrue();
            for (long bot : new long[]{103,106,107}) assertThat(isUsed(bot)).isFalse();
            assertThat(current.isUsed(102L, Set.of(2132L,2514L), new ReviewBotAssignmentGuardService.AssignmentScope(2514L,null,2L,null))).isFalse();
            assertThat(current.isUsed(105L, Set.of(2132L,2514L), new ReviewBotAssignmentGuardService.AssignmentScope(2514L,null,null,2L))).isFalse();
        });
    }

    @Test
    void waitingAssignmentSeesCommittedReservationDespiteEarlierRepeatableReadSnapshot() throws Exception {
        jdbc.update("INSERT INTO bots VALUES (101)");
        var locked = new CountDownLatch(1);
        var snapshot = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT bot_id FROM bots WHERE bot_id=101 FOR UPDATE", Long.class);
                locked.countDown();
                await(snapshot);
                jdbc.update("INSERT INTO reviews VALUES (1,2905,NULL,101,TRUE)");
            }));
            var second = pool.submit(() -> tx.execute(status -> {
                await(locked);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews", Integer.class)).isZero();
                snapshot.countDown();
                jdbc.queryForObject("SELECT bot_id FROM bots WHERE bot_id=101 FOR UPDATE", Long.class);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews", Integer.class)).isZero();
                return isUsed(101L);
            }));
            first.get(10,TimeUnit.SECONDS);
            assertThat(second.get(10,TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void organizationLockRejectsConcurrentDuplicateFilialAfterStaleSnapshot() throws Exception {
        var locked = new CountDownLatch(1);
        var snapshot = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tx.executeWithoutResult(status -> {
                identities.lockOrganization("777777");
                locked.countDown();
                await(snapshot);
                jdbc.update("INSERT INTO filial VALUES (5000,5000,'https://2gis.ru/firm/777777','777777')");
            }));
            var second = pool.submit(() -> tx.execute(status -> {
                await(locked);
                jdbc.queryForObject("SELECT COUNT(*) FROM filial", Integer.class);
                snapshot.countDown();
                identities.lockOrganization("777777");
                return identities.conflictingFilial("777777", null);
            }));
            first.get(10,TimeUnit.SECONDS);
            assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo(5000L);
        }
    }

    private boolean isUsed(Long bot) {
        return current.isUsed(bot, identities.relatedCompanyIds(2514L),
                new ReviewBotAssignmentGuardService.AssignmentScope(2514L, null, null, null));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
