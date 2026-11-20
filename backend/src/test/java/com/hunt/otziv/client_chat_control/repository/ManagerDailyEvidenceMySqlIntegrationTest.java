package com.hunt.otziv.client_chat_control.repository;

import com.hunt.otziv.client_chat_control.model.*;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The scheduler reads detached entities: mock-only tests cannot expose missing fetches. */
@Testcontainers
class ManagerDailyEvidenceMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manager_daily_evidence").withUsername("root").withPassword("fixture-password");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager em;
    static TransactionTemplate tx;
    static ClientChatUnansweredItemRepository repository;

    @BeforeAll static void start() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setPackagesToScan("com.hunt.otziv"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans)));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var manager = new JpaTransactionManager(factory.getObject()); manager.setDataSource(ds);
        tx = new TransactionTemplate(manager);
        repository = new JpaRepositoryFactory(em).getRepository(ClientChatUnansweredItemRepository.class);
    }

    @AfterAll static void stop() { if (factory != null) factory.destroy(); }

    @Test void bothDailyQueriesExposeManagerIdentityAfterTransactionCloses() {
        var at = LocalDateTime.of(2026, 11, 20, 12, 0);
        Long id = tx.execute(status -> {
            var user = User.builder().username("manager-fixture").fio("Менеджер проверки").build(); em.persist(user);
            var manager = new Manager(); manager.setUser(user); em.persist(manager);
            var item = new ClientChatUnansweredItem(); item.setManager(manager);
            item.setPlatform(ClientChatPlatform.WHATSAPP); item.setChatId("fixture");
            item.setFirstOpenedAt(at); item.setLastClientMessageAt(at); item.setClosedAt(at.plusMinutes(1));
            item.setResolutionType(ClientChatResolutionType.ACTION_COMPLETED); item.setResolvedByUserId(user.getId());
            em.persist(item); return manager.getId();
        });
        var daily = tx.execute(status -> repository.findDailyReportItems(id, at.minusDays(1), at.plusDays(1), ClientChatUnansweredStatus.OPEN));
        var actions = tx.execute(status -> repository.findManagerResolvedForDailyAudit(id, at.minusDays(1), at.plusDays(1),
                List.of(ClientChatResolutionType.ACTION_COMPLETED)));
        for (var detached : List.of(daily, actions)) {
            assertThat(detached).hasSize(1);
            assertThat(detached.getFirst().getManager().getUser().getFio()).isEqualTo("Менеджер проверки");
            assertThat(detached.getFirst().getManager().getUser().getUsername()).isEqualTo("manager-fixture");
        }
    }
}
