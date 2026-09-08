package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.api.NextOrderRequests;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.security.credentials.CredentialCipher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real owner queries and transaction proxies, not entity-shaped response fixtures. */
@Testcontainers
class NextOrderRequestAccessMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("next_order_boundary").withUsername("root").withPassword("root");
    private static LocalContainerEntityManagerFactoryBean factory;
    private static EntityManager em;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static NextOrderRequests access;
    private Long companyId;
    private Long createdId;
    private Long requestId;
    private List<Long> sources;

    @BeforeAll static void start() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setPackagesToScan("com.hunt.otziv"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans), "hibernate.generate_statistics", "true"));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var manager = new JpaTransactionManager(factory.getObject()); manager.setDataSource(ds);
        transaction = new TransactionTemplate(manager); jdbc = new JdbcTemplate(ds);
        var repository = new JpaRepositoryFactory(em).getRepository(NextOrderRequestRepository.class);
        var proxy = new ProxyFactory(new NextOrderRequestAccessService(repository)); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        access = (NextOrderRequests) proxy.getProxy();
    }

    @AfterAll static void stop() { if (factory != null) factory.destroy(); }

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM next_order_requests"); jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM filial"); jdbc.update("DELETE FROM companies"); jdbc.update("DELETE FROM order_statuses");
        transaction.executeWithoutResult(status -> {
            var company = new Company(); company.setTitle("Company"); company.setTelephone("79990000001"); company.setCity("City");
            company.setCreateDate(java.time.LocalDate.of(2026, 9, 1));
            em.persist(company); companyId = company.getId();
            var filial = new Filial(); filial.setCompany(company); filial.setTitle("Branch"); em.persist(filial);
            var state = new OrderStatus(); state.setTitle("Новый"); em.persist(state);
            var created = new Order(); created.setCompany(company); created.setFilial(filial); created.setStatus(state);
            em.persist(created); createdId = created.getId();
            var sourceA = new Order(); sourceA.setCompany(company); em.persist(sourceA);
            var sourceB = new Order(); sourceB.setCompany(company); em.persist(sourceB);
            var sourceC = new Order(); sourceC.setCompany(company); em.persist(sourceC);
            sources = List.of(sourceA.getId(), sourceB.getId(), sourceC.getId());
            var first = request(company, filial, sourceA, created, NextOrderRequestStatus.FAILED, "first failure");
            em.persist(first); requestId = first.getId();
            em.persist(request(company, filial, sourceB, created, NextOrderRequestStatus.FAILED, "latest failure"));
            em.persist(request(company, null, sourceC, null, NextOrderRequestStatus.PENDING, null));
        });
        for (int index = 0; index < sources.size(); index++) {
            jdbc.update("UPDATE next_order_requests SET created_at=?,updated_at=? WHERE source_order_id=?",
                    "2026-09-0" + (index + 1) + " 10:00:00", "2026-09-0" + (index + 1) + " 10:00:00", sources.get(index));
        }
    }

    @Test void companyAggregationAndArchiveBlockersUseOwnerStatusRulesWithoutLoadingEntities() {
        var statistics = factory.getObject().unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        var summaries = access.companySummaries(List.of(companyId));
        assertThat(summaries.get(companyId)).isEqualTo(new NextOrderRequests.CompanySummary(3, 2, null, "latest failure"));
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(access.hasOpenRequest(sources.getFirst())).isTrue();
        jdbc.update("UPDATE next_order_requests SET request_status='CREATED' WHERE source_order_id=?", sources.getFirst());
        assertThat(access.hasOpenRequest(sources.getFirst())).isFalse();
        assertThat(access.hasOpenRequest(-1L)).isFalse();
    }

    @Test void detachedCreatedOrderScalarsPreserveBatchOrderAndRepeatedSuccessorWithoutEntityFetches() {
        var statistics = factory.getObject().unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        var result = access.createdOrdersForSources(sources);
        assertThat(result).containsExactly(
                new NextOrderRequests.CreatedOrder(sources.get(0), createdId, "Company", "Branch", "Новый"),
                new NextOrderRequests.CreatedOrder(sources.get(1), createdId, "Company", "Branch", "Новый"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
        statistics.clear();
        assertThat(access.createdOrdersForSources(List.of())).isEmpty();
        assertThat(access.companySummaries(List.of())).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test void lockedOriginRequiresCallerTransactionAndKeepsRequestLockUntilItsRollback() throws Exception {
        jdbc.update("UPDATE next_order_requests SET request_status='CREATED' WHERE next_order_request_id=?", requestId);
        assertThatThrownBy(() -> access.lockCreatedOrigin(createdId)).isInstanceOf(IllegalTransactionStateException.class);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var competing = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transaction.executeWithoutResult(status -> {
                var order = em.find(Order.class, createdId, LockModeType.PESSIMISTIC_WRITE);
                assertThat(access.lockCreatedOrigin(createdId)).isTrue();
                order.setCounter(7); em.flush(); locked.countDown(); await(release);
                throw new IllegalStateException("late billing rollback");
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> { competing.countDown(); return jdbc.update(
                    "UPDATE next_order_requests SET attempts=attempts+1 WHERE next_order_request_id=?", requestId); });
            try {
                assertThat(competing.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT order_counter FROM orders WHERE order_id=?", Integer.class, createdId)).isZero();
        assertThat(jdbc.queryForObject("SELECT attempts FROM next_order_requests WHERE next_order_request_id=?", Integer.class, requestId)).isEqualTo(1);
    }

    private static NextOrderRequest request(Company company, Filial filial, Order source, Order created,
            NextOrderRequestStatus status, String error) {
        var result = new NextOrderRequest(); result.setCompany(company); result.setFilial(filial); result.setSourceOrder(source);
        result.setCreatedOrder(created); result.setStatus(status); result.setErrorMessage(error); return result;
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
