package com.hunt.otziv.archive.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ArchiveRewardLedgerGuardMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("archive_reward_guard").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private OrderArchiveDryRunRepository repository;
    private TransactionTemplate transactions;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS contractor_reward_ledger");
        jdbc.execute("DROP TABLE IF EXISTS archive_candidate_orders");
        jdbc.execute("DROP TABLE IF EXISTS archive_orders");
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("DROP TABLE IF EXISTS salary_paid_order_status_guard");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_status BIGINT,UNIQUE KEY(order_id,order_status)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE archive_orders(order_id BIGINT PRIMARY KEY,order_status BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE archive_candidate_orders(order_id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE salary_paid_order_status_guard(order_status_id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO salary_paid_order_status_guard VALUES(7)");
        jdbc.execute("""
                CREATE TABLE contractor_reward_ledger (
                    id BIGINT PRIMARY KEY, source_zp_id BIGINT NOT NULL, order_id BIGINT,
                    payment_status_guard BIGINT, active BOOLEAN NOT NULL, amount_kopecks BIGINT NOT NULL,
                    active_order_guard BIGINT GENERATED ALWAYS AS
                        (CASE WHEN active=1 AND order_id>0 THEN order_id ELSE NULL END) STORED,
                    CONSTRAINT ck_ledger_active_order_paid_guard CHECK(active_order_guard IS NULL OR payment_status_guard IS NOT NULL),
                    CONSTRAINT fk_ledger_paid_status_guard FOREIGN KEY(payment_status_guard) REFERENCES salary_paid_order_status_guard(order_status_id),
                    CONSTRAINT fk_ledger_active_order_paid_status FOREIGN KEY(active_order_guard,payment_status_guard) REFERENCES orders(order_id,order_status)
                ) ENGINE=InnoDB
                """);
        jdbc.update("INSERT INTO orders VALUES(101,7)");
        jdbc.update("INSERT INTO archive_candidate_orders VALUES(101)");
        jdbc.update("INSERT INTO contractor_reward_ledger(id,source_zp_id,order_id,payment_status_guard,active,amount_kopecks) VALUES(1,900,101,7,1,150000)");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_371__archive_reward_ledger_paid_order_guard.sql")).execute(ds);
        repository = new OrderArchiveDryRunRepository(new NamedParameterJdbcTemplate(ds));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test
    void archiveKeepsEarnedMoneyAndPaidConstraintAndCanReturnToLiveOrder() {
        Map<String,Object> before = financialSnapshot();
        jdbc.update("INSERT INTO archive_orders SELECT * FROM orders");
        transactions.executeWithoutResult(status -> {
            repository.bindPreparedContractorRewardsToArchive();
            assertThat(jdbc.update("DELETE FROM orders WHERE order_id=101")).isOne();
        });
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT archived_order_id FROM contractor_reward_ledger", Long.class)).isEqualTo(101);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM archive_orders WHERE order_id=101"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE archive_orders SET order_status=8 WHERE order_id=101"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO orders VALUES(101,7)");
        jdbc.update("UPDATE contractor_reward_ledger SET archived_order_id=NULL WHERE id=1");
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET order_status=8 WHERE order_id=101"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void incompleteOrWrongStatusArchiveNeverReleasesLivePaidGuard() {
        repository.bindPreparedContractorRewardsToArchive();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM orders WHERE order_id=101"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO archive_orders VALUES(101,8)");
        assertThatThrownBy(repository::bindPreparedContractorRewardsToArchive)
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT active FROM contractor_reward_ledger", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT archived_order_id FROM contractor_reward_ledger", Long.class)).isNull();
    }

    @Test
    void failedArchiveRollsBackRoutingAndCannotAttachAnotherOrder() {
        jdbc.update("INSERT INTO archive_orders VALUES(101,7),(102,7)");
        transactions.executeWithoutResult(status -> {
            repository.bindPreparedContractorRewardsToArchive();
            jdbc.update("DELETE FROM orders WHERE order_id=101");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT archived_order_id FROM contractor_reward_ledger", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isOne();
        assertThatThrownBy(() -> jdbc.update("UPDATE contractor_reward_ledger SET archived_order_id=102 WHERE id=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("ck_ledger_archive_order_identity");
    }

    private Map<String,Object> financialSnapshot() {
        return jdbc.queryForMap("SELECT id,source_zp_id,order_id,payment_status_guard,active,amount_kopecks FROM contractor_reward_ledger WHERE id=1");
    }
}
