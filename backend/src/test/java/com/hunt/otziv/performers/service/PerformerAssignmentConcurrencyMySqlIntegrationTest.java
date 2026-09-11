package com.hunt.otziv.performers.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.c_cities.service.CityDistanceService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.performers.model.*;
import com.hunt.otziv.performers.repository.*;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.security.credentials.CredentialEncryptionProperties;
import com.hunt.otziv.security.credentials.EncryptedCredentialConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Exercises the production performer lock and mutation code with real JPA/MySQL
 * transactions. External modules/notification delivery are replaced at their APIs. */
@Testcontainers
class PerformerAssignmentConcurrencyMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("performer_concurrency").withUsername("root").withPassword("root");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager em;
    static JdbcTemplate jdbc;
    static JpaTransactionManager manager;
    static TransactionTemplate transaction;
    PerformerAssignmentService service;
    PerformerNotificationService notifications;
    PerformerMutationLockService locks;
    LocalDateTime now = LocalDateTime.of(2026, 9, 7, 12, 0);

    @BeforeAll
    static void persistence() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("com.hunt.otziv");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        var beans = new DefaultListableBeanFactory();
        var cipher = new CredentialCipher(new CredentialEncryptionProperties());
        beans.registerSingleton("credentialCipher", cipher);
        beans.registerSingleton("encryptedCredentialConverter", new EncryptedCredentialConverter(cipher));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.show_sql", "false",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans)));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        manager = new JpaTransactionManager(factory.getObject());
        manager.setDataSource(ds);
        transaction = new TransactionTemplate(manager);
        jdbc = new JdbcTemplate(ds);
        // Hibernate creates the business entities. Use the actual durable-intent DDL
        // from Flyway for the JDBC-owned tables (their earlier ALTERs already exist).
        String delivery = new ClassPathResource("db/migration/V1_10_288__performer_scoped_delivery.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        new ResourceDatabasePopulator(new ByteArrayResource(delivery.substring(
                delivery.indexOf("CREATE TABLE performer_notification_intents")).getBytes(StandardCharsets.UTF_8)))
                .execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_300__performer_readiness_intent_index.sql"))
                .execute(ds);
    }

    @AfterAll static void close() { if (factory != null) factory.destroy(); }

    @BeforeEach
    void setupService() {
        var orders = mock(OrderRepository.class);
        when(orders.findByIdForCounterUpdate(anyLong())).thenAnswer(call ->
                java.util.Optional.ofNullable(em.find(Order.class, call.getArgument(0, Long.class), LockModeType.PESSIMISTIC_WRITE)));
        var reviews = mock(ReviewRepository.class);
        var canonical = new OrderAggregateMutationLockService(orders, mock(OrderDetailsRepository.class), reviews);
        locks = new PerformerMutationLockService(canonical, jdbc, em);
        var assignments = mock(ReviewPerformerAssignmentRepository.class);
        var offers = mock(ReviewPerformerOfferRepository.class);
        when(offers.findByAssignmentIdAndStatuses(anyLong(), any())).thenAnswer(call ->
                em.createQuery("SELECT o FROM ReviewPerformerOffer o WHERE o.assignment.id = :id AND o.status = :status", ReviewPerformerOffer.class)
                        .setParameter("id", call.getArgument(0, Long.class)).setParameter("status", PerformerOfferStatus.OFFERED).getResultList());
        when(offers.findExpiredIds(any(), any())).thenAnswer(call -> jdbc.query(
                "SELECT offer_id FROM review_performer_offers WHERE status='OFFERED' AND expires_at <= ?",
                (rs,row) -> rs.getLong(1), now));
        notifications = mock(PerformerNotificationService.class);
        var notificationRepository = mock(PerformerNotificationRepository.class);
        when(notificationRepository.now()).thenReturn(now);
        service = new PerformerAssignmentService(orders, reviews, mock(UserRepository.class),
                mock(PerformerProfileRepository.class), mock(PerformerCityRepository.class), assignments, offers,
                mock(PerformerTaskEvidenceRepository.class), mock(PerformerPayoutRepository.class),
                mock(PerformerAssignmentMapper.class), mock(PerformerTelegramNotificationService.class),
                mock(OrderStatusTransitionService.class), mock(PerformerRolloutService.class),
                mock(PerformerAssignmentScreenshotStorage.class), mock(CityDistanceService.class),
                locks, notifications, notificationRepository, manager);
        ReflectionTestUtils.setField(service, "offerBatchSize", 20);
    }

    @Test
    void simultaneousAcceptsCommitOnceAndNotifyOnce() throws Exception {
        long id = seed(false);
        var results = race(() -> accept(id), () -> accept(id));
        assertThat(results).containsExactlyInAnyOrder("accepted", "conflict");
        assertThat(status(id)).isEqualTo("ACCEPTED");
        verify(notifications, times(1)).accepted(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void acceptVersusDeclineUsesTheCurrentLockedStateInBothCommitOrders(boolean acceptFirst) throws Exception {
        long id=seed(false);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->transaction.execute(tx->{
                locks.offer(id);locked.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("commit barrier timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return acceptFirst?accept(id):decline(id);
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->acceptFirst?decline(id):accept(id));
            // A real InnoDB lock wait proves the losing command entered its mutation
            // while the winner still owned Order/Assignment/Offer, rather than after it.
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->
                    jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0);
            assertThat(second.isDone()).isFalse();release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(acceptFirst?"accepted":"declined");
            assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(acceptFirst?"declined":"conflict");
            assertThat(status(id)).isEqualTo(acceptFirst?"ACCEPTED":"DECLINED");
            assertThat(jdbc.queryForObject("SELECT a.status FROM review_performer_assignments a JOIN review_performer_offers o ON o.assignment_id=a.assignment_id WHERE o.offer_id=?",String.class,id))
                    .isEqualTo(acceptFirst?"ACCEPTED":"CREATED");
            assertThat(expiredCount(id)).isZero();
            assertThat(jdbc.queryForObject("SELECT p.completed_count+p.cancelled_count FROM performer_profiles p JOIN review_performer_offers o ON o.performer_id=p.performer_id WHERE o.offer_id=?",Integer.class,id)).isZero();
            if(acceptFirst)verify(notifications,times(1)).accepted(any());else verifyNoInteractions(notifications);
        } finally {release.countDown();}
    }

    @Test
    void twoExpirySchedulersIncrementCounterOnlyOnce() throws Exception {
        long id = seed(true);
        var results = race(service::expireOffers, service::expireOffers);
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(expiredCount(id)).isEqualTo(1);
    }

    @Test
    void acceptAtExpiredDeadlineCannotOverwriteConcurrentExpiry() throws Exception {
        long id = seed(true);
        race(() -> { assertThat(accept(id)).isEqualTo("conflict"); return 0; }, service::expireOffers);
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(expiredCount(id)).isEqualTo(1);
        verifyNoInteractions(notifications);
    }

    @Test
    void managedGenerationFlushAndLaterDirtyFlushKeepExactIntentMarker() {
        long assignmentId = assignmentId(seed(false));
        var repository = new PerformerNotificationRepository(jdbc);
        var delivery = new PerformerNotificationService(repository, locks, mock(PerformerTelegramNotificationService.class), em);

        transaction.executeWithoutResult(tx -> {
            var assignment = locks.assignment(assignmentId);
            assignment.setStatus(PerformerAssignmentStatus.WAITING_PUBLICATION);
            assignment.setPublicationGeneration(1);
            assignment.setPublishAvailableAt(now);
            delivery.ready(assignment);
            assertThat(marker(assignmentId)).isEqualTo(1);
            assignment.setManagerNote("Second dirty flush must not overwrite the JDBC marker");
        });

        assertThat(marker(assignmentId)).isEqualTo(1);
        assertThat(intentCount(assignmentId, 1)).isEqualTo(1);
        assertThat(repository.readyAssignmentIds(100)).doesNotContain(assignmentId);
    }

    @Test
    void managedGenerationIntentAndMarkerRollbackAsOneTransaction() {
        long assignmentId = assignmentId(seed(false));
        var delivery = new PerformerNotificationService(new PerformerNotificationRepository(jdbc), locks,
                mock(PerformerTelegramNotificationService.class), em);

        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            var assignment = locks.assignment(assignmentId);
            assignment.setStatus(PerformerAssignmentStatus.WAITING_PUBLICATION);
            assignment.setPublicationGeneration(1);
            delivery.ready(assignment);
            assertThat(marker(assignmentId)).isEqualTo(1);
            throw new IllegalStateException("whole workflow failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(marker(assignmentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT publication_generation FROM review_performer_assignments WHERE assignment_id=?", Long.class, assignmentId)).isZero();
        assertThat(intentCount(assignmentId, 1)).isZero();
    }

    @Test
    void concurrentGenerationChangeAndReconciliationKeepMarkerOnCommittedCurrentCycle() throws Exception {
        long assignmentId = assignmentId(seed(false));
        var repository = new PerformerNotificationRepository(jdbc);
        var delivery = new PerformerNotificationService(repository, locks, mock(PerformerTelegramNotificationService.class), em);
        transaction.executeWithoutResult(tx -> {
            var assignment = locks.assignment(assignmentId);
            assignment.setStatus(PerformerAssignmentStatus.WAITING_PUBLICATION);
            assignment.setPublicationGeneration(1);
            delivery.ready(assignment);
        });

        race(() -> transaction.execute(tx -> {
                    var assignment = locks.assignment(assignmentId);
                    assignment.setPublicationGeneration(2);
                    delivery.ready(assignment);
                    return 1;
                }),
                () -> transaction.execute(tx -> delivery.reconcileReady(assignmentId) ? 1 : 0));

        assertThat(marker(assignmentId)).isEqualTo(2);
        assertThat(intentCount(assignmentId, 1)).isEqualTo(1);
        assertThat(intentCount(assignmentId, 2)).isEqualTo(1);
        assertThat(repository.readyAssignmentIds(100)).doesNotContain(assignmentId);
    }

    private long assignmentId(long offerId) {
        return jdbc.queryForObject("SELECT assignment_id FROM review_performer_offers WHERE offer_id=?", Long.class, offerId);
    }

    private long marker(long assignmentId) {
        return jdbc.queryForObject("SELECT readiness_intent_generation FROM review_performer_assignments WHERE assignment_id=?", Long.class, assignmentId);
    }

    private long intentCount(long assignmentId, long generation) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents WHERE operation_key=?", Long.class,
                "READY:" + assignmentId + ":" + generation);
    }

    private long seed(boolean expired) {
        return transaction.execute(tx -> {
            var user = User.builder().username("performer-" + UUID.randomUUID()).active(true).telegramChatId(700L + System.nanoTime()).build();
            em.persist(user);
            var performer = PerformerProfile.builder().user(user).status(PerformerProfileStatus.ACTIVE).build();
            em.persist(performer);
            var order = Order.builder().build(); em.persist(order);
            var review = Review.builder().build(); em.persist(review);
            var assignment = ReviewPerformerAssignment.builder().order(order).review(review).status(PerformerAssignmentStatus.OFFERING).build();
            em.persist(assignment);
            var offer = ReviewPerformerOffer.builder().assignment(assignment).performer(performer)
                    .status(PerformerOfferStatus.OFFERED).deliveryState("DELIVERED")
                    .telegramChatId(user.getTelegramChatId()).offeredAt(now.minusMinutes(2))
                    .expiresAt(expired ? now.minusSeconds(1) : now.plusMinutes(10)).build();
            em.persist(offer);
            return offer.getId();
        });
    }

    private String accept(long id) {
        try {
            transaction.executeWithoutResult(tx -> {
                Long chat = jdbc.queryForObject("SELECT telegram_chat_id FROM review_performer_offers WHERE offer_id=?", Long.class, id);
                service.acceptOfferFromTelegram(id, chat, chat);
            });
            return "accepted";
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
            return "conflict";
        }
    }

    private String decline(long id) {
        transaction.executeWithoutResult(tx->{
            Long chat=jdbc.queryForObject("SELECT telegram_chat_id FROM review_performer_offers WHERE offer_id=?",Long.class,id);
            service.declineOfferFromTelegram(id,chat,chat);
        });
        return "declined"; // an already accepted/declined offer is an intentional no-op
    }

    private String status(long id) { return jdbc.queryForObject("SELECT status FROM review_performer_offers WHERE offer_id=?", String.class,id); }
    private int expiredCount(long id) { return jdbc.queryForObject("SELECT p.expired_offer_count FROM performer_profiles p JOIN review_performer_offers o ON o.performer_id=p.performer_id WHERE o.offer_id=?",Integer.class,id); }

    private <T> List<T> race(Callable<T> left, Callable<T> right) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> a = () -> { ready.countDown(); if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout"); return left.call(); };
            Callable<T> b = () -> { ready.countDown(); if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout"); return right.call(); };
            var first=executor.submit(a);var second=executor.submit(b);
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }
}
