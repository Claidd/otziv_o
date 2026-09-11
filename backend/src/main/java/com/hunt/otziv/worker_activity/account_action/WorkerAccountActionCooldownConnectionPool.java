package com.hunt.otziv.worker_activity.account_action;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Independent, bounded pool: the enclosing card transaction may already hold a main-pool connection.
 * This holder deliberately is not a DataSource bean, so Boot still configures the primary datasource.
 */
@Component
public class WorkerAccountActionCooldownConnectionPool implements DisposableBean, AutoCloseable {
    private final HikariDataSource dataSource;

    public WorkerAccountActionCooldownConnectionPool(DataSource primaryDataSource) throws SQLException {
        HikariConfig config = new HikariConfig();
        primaryDataSource.unwrap(HikariDataSource.class).copyStateTo(config);
        config.setPoolName("worker-account-action-cooldown");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setAutoCommit(true);
        config.setReadOnly(false);
        config.setInitializationFailTimeout(-1);
        config.setMetricRegistry(null);
        config.setMetricsTrackerFactory(null);
        config.setHealthCheckRegistry(null);
        this.dataSource = new HikariDataSource(config);
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    public PlatformTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource);
    }

    @Override
    public void destroy() {
        close();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
