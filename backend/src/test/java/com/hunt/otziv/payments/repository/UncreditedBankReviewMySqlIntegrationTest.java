package com.hunt.otziv.payments.repository;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.*;
import com.hunt.otziv.security.credentials.CredentialCipher;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Runs the reconciliation selector on actual MySQL/JPQL, including old failed claims. */
@Testcontainers
class UncreditedBankReviewMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager em;
    static TransactionTemplate tx;
    static SpecialistManualPaymentReviewRepository reviews;

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
        tx = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        reviews = new JpaRepositoryFactory(em).getRepository(SpecialistManualPaymentReviewRepository.class);
    }

    @AfterAll static void stop() { if (factory != null) factory.destroy(); }

    @ParameterizedTest
    @ValueSource(strings = {"unpaid", "bank-paid", "manual-paid", "test-paid", "credited", "resolved", "amount-changed", "order-unpaid", "receipt-amount-changed"})
    void selectsOnlyMatchingUncreditedClaimsWithAnActualBankReceipt(String state) {
        tx.executeWithoutResult(status -> {
            em.createQuery("delete from SpecialistManualPaymentReview").executeUpdate();
            em.createQuery("delete from PaymentLink").executeUpdate();
            em.createQuery("delete from Order").executeUpdate();
            var order = new Order();
            if (!state.equals("order-unpaid")) { order.setComplete(true); order.setPayDay(java.time.LocalDate.now()); }
            em.persist(order);
            var now = LocalDateTime.now();
            var link = new PaymentLink(); link.setOrder(order); link.setToken("bank-review-" + state);
            link.setDescription("Owner bank payment"); link.setAmountKopecks(250_000L);
            link.setPaymentMethod(PaymentMethod.SBP_QR); link.setTbankPaymentId("original-operation");
            link.setStatus(state.equals("unpaid") ? PaymentLinkStatus.INITIATED : PaymentLinkStatus.CONFIRMED);
            if (!state.equals("unpaid")) { link.setPaidAt(now); link.setConfirmedAmountKopecks(250_000L); }
            if (state.equals("receipt-amount-changed")) link.setConfirmedAmountKopecks(240_000L);
            if (state.equals("test-paid")) link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
            if (state.equals("manual-paid")) link.setManualConfirmedAt(now);
            link.setExpiresAt(now.plusDays(7)); em.persist(link);
            var review = new SpecialistManualPaymentReview(); review.setOrderId(order.getId());
            review.setPaymentLinkId(link.getId()); review.setAmountKopecks(state.equals("amount-changed") ? 260_000L : 250_000L);
            review.setRecipientType("MANAGER"); review.setRecipientKey("PROFILE:33");
            review.setRequestedByUserId(33L); review.setRequestedBy("manager"); review.setReason("По реквизитам");
            review.setRequestedAt(now); review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
            if (state.equals("credited")) review.setCreditedAt(now);
            if (state.equals("resolved")) { review.setResolvedAt(now); review.setStatus(SpecialistManualPaymentReviewStatus.SUPERSEDED); }
            em.persist(review); em.flush();

            var selected = reviews.findUncreditedBankConfirmedIds(
                    EnumSet.of(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION), PageRequest.of(0, 30));
            if (state.equals("bank-paid")) assertThat(selected).containsExactly(review.getId());
            else assertThat(selected).isEmpty();
        });
    }
}
