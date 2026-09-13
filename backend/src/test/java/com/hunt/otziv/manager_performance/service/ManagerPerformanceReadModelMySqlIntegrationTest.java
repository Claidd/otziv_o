package com.hunt.otziv.manager_performance.service;

import com.hunt.otziv.client_chat_control.dto.ClientChatPerformanceSample;
import com.hunt.otziv.client_chat_control.model.*;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real entity mapping and real repository JPQL; same period, ownership and score inputs. */
@Testcontainers
class ManagerPerformanceReadModelMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manager_score_read_model").withUsername("root").withPassword("fixture-password");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager em;
    static TransactionTemplate tx;
    static JdbcTemplate jdbc;
    static ClientChatUnansweredItemRepository repository;
    static final List<String> SQL = new ArrayList<>();

    @BeforeAll static void start() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setPackagesToScan("com.hunt.otziv"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans),
                "hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> { SQL.add(sql); return sql; }));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var manager = new JpaTransactionManager(factory.getObject()); manager.setDataSource(ds);
        tx = new TransactionTemplate(manager); jdbc = new JdbcTemplate(ds);
        repository = new JpaRepositoryFactory(em).getRepository(ClientChatUnansweredItemRepository.class);
    }
    @AfterAll static void stop() { if (factory != null) factory.destroy(); }

    @Test void projectionPreservesEveryBoundaryRowAndNeverLoadsConversationText() {
        var from = LocalDateTime.of(2026, 9, 1, 0, 0);
        var to = from.plusDays(13).minusNanos(1000);
        List<Manager> managers = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (int m = 0; m < 3; m++) {
                var manager = new Manager(); em.persist(manager); managers.add(manager);
                for (var created : List.of(from.minusNanos(1000), from, to, to.plusNanos(1000))) {
                    for (var closed : Arrays.asList(null, from.minusNanos(1000), from, to, to.plusNanos(1000))) {
                        for (var state : List.of(ClientChatUnansweredStatus.OPEN, ClientChatUnansweredStatus.ANSWERED)) {
                            var item = new ClientChatUnansweredItem(); item.setManager(manager);
                            item.setPlatform(ClientChatPlatform.TELEGRAM); item.setChatId("fixture");
                            item.setStatus(state); item.setLastClientMessageAt(created); item.setClosedAt(closed);
                            item.setLastMessageText("Synthetic text ".repeat(1000));
                            em.persist(item); em.flush();
                            jdbc.update("UPDATE client_chat_unanswered_items SET created_at=? WHERE id=?", created, item.getId());
                        }
                    }
                }
            }
        });
        var visible = managers.subList(0, 2);
        var expected = tx.execute(status -> repository.findPerformanceItems(visible, from, to, ClientChatUnansweredStatus.OPEN)
                .stream().map(item -> new ClientChatPerformanceSample(item.getId(), item.getManager().getId(),
                        item.getStatus(), item.getLastClientMessageAt(), item.getClosedAt())).toList());
        SQL.clear();
        var actual = tx.execute(status -> repository.findPerformanceSamples(visible, from, to, ClientChatUnansweredStatus.OPEN));
        assertThat(actual).isNotEmpty().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).allSatisfy(row -> assertThat(row.getManagerId()).isIn(visible.stream().map(Manager::getId).toList()));
        assertThat(SQL).hasSize(1);
        assertThat(SQL.getFirst()).doesNotContain("last_message_text", "resolution_reply_text", "sender_name", "join managers");
    }
}
