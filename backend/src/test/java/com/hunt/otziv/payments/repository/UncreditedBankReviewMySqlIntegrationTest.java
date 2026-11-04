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
    @ValueSource(strings={"due","future","fresh-claim","stale-claim","disputed","resolved","credited","financial-conflict","notification-only"})
    void creditSelectorHonorsDurableRetryTimeAndNeverRetriesDisputesOrFinancialConflicts(String state) {
        tx.executeWithoutResult(status -> {
            em.createQuery("delete from SpecialistManualPaymentReview").executeUpdate();
            var now=LocalDateTime.now().withNano(0);
            var r=new SpecialistManualPaymentReview(); r.setOrderId(27590L); r.setPaymentLinkId(8791L);
            r.setAmountKopecks(150000L); r.setRecipientKey("PROFILE:5");
            r.setRequestedByUserId(17L); r.setRequestedBy("manager"); r.setReason("Чек клиента");
            r.setRequestedAt(now.minusDays(2)); r.setDeadlineAt(now.minusDays(1));
            r.setStatus(SpecialistManualPaymentReviewStatus.WAITING);
            r.setCreditClaimedAt(now.minusSeconds(5)); r.setCreditAttemptCount(8);
            r.setCreditNextAttemptAt(state.equals("future")?now.plusMinutes(15):now.minusSeconds(1));
            if (state.endsWith("claim")) {
                r.setStatus(SpecialistManualPaymentReviewStatus.SETTLING);
                r.setCreditClaimedAt(state.equals("stale-claim")?now.minusMinutes(16):now.minusMinutes(1));
            }
            if (state.equals("disputed")) r.setDisputedAt(now);
            if (state.equals("resolved")) r.setResolvedAt(now);
            if (state.equals("credited")) r.setCreditedAt(now);
            if (state.equals("financial-conflict")||state.equals("notification-only")) {
                r.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
                r.setLastError(state.equals("notification-only")?"Уведомление не доставлено":"Требуется сверка возврата");
            }
            em.persist(r); em.flush();
            var ids=reviews.findUncreditedIds(EnumSet.of(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY,
                    SpecialistManualPaymentReviewStatus.WAITING,SpecialistManualPaymentReviewStatus.SETTLING,
                    SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION),now.minusMinutes(15),now,PageRequest.of(0,30));
            if (java.util.Set.of("due","stale-claim","notification-only").contains(state)) assertThat(ids).containsExactly(r.getId());
            else assertThat(ids).isEmpty();
        });
    }

    @Test void retryMigrationReopensOnlyUncreditedUndisputedOldLeaseFailuresWithoutChangingMoneyOrDeadline() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.update("DELETE FROM specialist_manual_payment_reviews");
        jdbc.execute("ALTER TABLE specialist_manual_payment_reviews DROP COLUMN credit_attempt_count, DROP COLUMN credit_next_attempt_at");
        var failure="Учёт распределения занят возвратом или сверкой; повторите операцию позже";
        for(int id=1;id<=5;id++) jdbc.update("""
                INSERT INTO specialist_manual_payment_reviews(id,order_id,payment_link_id,amount_kopecks,
                recipient_key,recipient_type,requested_by_user_id,requested_by,reason,status,requested_at,deadline_at,
                credited_at,disputed_at,resolved_at,last_error,attempt_count)
                VALUES(?,27590,?,150000,'PROFILE:5','SPECIALIST',17,'manager','Чек клиента','NEEDS_ATTENTION',
                '2026-11-02 21:21:35','2026-11-03 21:21:35',?,?,?, ?,1)
                """,id,8790+id,id==2?java.sql.Timestamp.valueOf("2026-11-02 21:22:00"):null,
                id==3?java.sql.Timestamp.valueOf("2026-11-02 21:22:00"):null,
                id==4?java.sql.Timestamp.valueOf("2026-11-02 21:22:00"):null,id==5?"Исход отмены банковского платежа неизвестен":failure);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V1_10_370__manual_payment_credit_retries.sql")).execute(ds);
        assertThat(jdbc.queryForList("SELECT id FROM specialist_manual_payment_reviews WHERE status='WAITING'",Long.class)).containsExactly(1L);
        assertThat(jdbc.queryForObject("SELECT SUM(amount_kopecks) FROM specialist_manual_payment_reviews",Long.class)).isEqualTo(750000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM specialist_manual_payment_reviews WHERE deadline_at='2026-11-03 21:21:35' AND recipient_key='PROFILE:5'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM specialist_manual_payment_reviews WHERE credited_at IS NOT NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT credit_next_attempt_at IS NOT NULL FROM specialist_manual_payment_reviews WHERE id=1",Boolean.class)).isTrue();
    }

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
