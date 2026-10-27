package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.model.*;
import com.hunt.otziv.manager_daily_summary.repository.*;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual production mappings/queries, independent InnoDB transactions and Hibernate flushes. */
@Testcontainers
class ManagerReportReviewSessionConcurrencyMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("audit_session_concurrency").withUsername("root").withPassword("fixture-password");
    private static LocalContainerEntityManagerFactoryBean factory;
    private static EntityManager em;
    private static JpaTransactionManager transactions;
    private static TransactionTemplate tx;
    private static JdbcTemplate jdbc;
    private static ManagerReportReviewSessionRepository sessions;
    private static ManagerReportReviewEventRepository events;
    private Long id;
    private LocalDateTime now;
    private ManagerReportReviewAccessPolicy access;
    private ManagerReportReviewAiAvailabilityService availability;

    @BeforeAll
    static void start() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds); factory.setPackagesToScan("com.hunt.otziv");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans)));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        transactions = new JpaTransactionManager(factory.getObject()); transactions.setDataSource(ds);
        tx = new TransactionTemplate(transactions);
        var repositories = new JpaRepositoryFactory(em);
        sessions = repositories.getRepository(ManagerReportReviewSessionRepository.class);
        events = repositories.getRepository(ManagerReportReviewEventRepository.class);
    }

    @AfterAll static void stop() { if (factory != null) factory.destroy(); }

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM manager_report_review_events");
        jdbc.update("DELETE FROM manager_report_review_sessions");
        now = LocalDateTime.now().withNano(0);
        access = mock(ManagerReportReviewAccessPolicy.class);
        availability = advised(new ManagerReportReviewAiAvailabilityService(sessions, events, access));
        id = tx.execute(status -> {
            var manager = new Manager(); em.persist(manager);
            var review = new ManagerReportReviewSession();
            review.setManager(manager); review.setManagerUserId(17L); review.setManagerName("Synthetic manager");
            review.setRecipientChatId(700L); review.setSummaryDate(LocalDate.now());
            review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
            review.setReportSnapshot("Synthetic context"); review.setQuestionsJson("[]");
            review.setAnswersJson("[\"old\"]"); review.setDeadlineStartedAt(now.minusHours(4));
            em.persist(review); em.flush(); return review.getId();
        });
    }

    @Test
    void staleManagedReminderFlushCannotOverwriteCommittedAnswer() {
        tx.executeWithoutResult(status -> {
            var stale = sessions.findById(id).orElseThrow();
            independently(() -> tx.executeWithoutResult(other -> {
                var answered = sessions.findForUpdateById(id).orElseThrow();
                answered.setAnswersJson("[\"old\",\"new structured answer\"]");
                answered.setCurrentQuestionIndex(1);
                answered.setAnswerQuality("NEEDS_CLARIFICATION");
                em.flush();
            }));
            // Reproduces the former reminder's stale managed snapshot and commit-time dirty flush.
            stale.setReminderOneSentAt(now);
            em.flush();
        });
        var current = current();
        assertThat(current.getAnswersJson()).contains("new structured answer");
        assertThat(current.getCurrentQuestionIndex()).isEqualTo(1);
        assertThat(current.getAnswerQuality()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(current.getReminderOneSentAt()).isEqualTo(now);
    }

    @Test
    void resumeUsesCurrentPauseAfterStaleReadAndPreservesConcurrentAnswer() {
        tx.executeWithoutResult(status -> {
            var stale = sessions.findById(id).orElseThrow();
            independently(() -> tx.executeWithoutResult(other -> {
                var updated = sessions.findForUpdateById(id).orElseThrow();
                updated.setAnswersJson("[\"old\",\"deferred answer\"]");
                assertThat(availability.pause(updated, now.minusSeconds(120), "test", "temporary failure")).isTrue();
            }));
            assertThat(stale.getAiUnavailableStartedAt()).isNull();
            assertThat(availability.resume(stale, now, "test")).isTrue();
            em.flush();
        });
        var current = current();
        assertThat(current.getAnswersJson()).contains("deferred answer");
        assertThat(current.getAiUnavailableStartedAt()).isNull();
        assertThat(current.getAiUnavailableSeconds()).isEqualTo(120);
        assertThat(current.getDeadlineStartedAt()).isEqualTo(now.minusHours(4).plusSeconds(120));
        assertThat(eventCount("AI_VERIFICATION_PAUSED")).isEqualTo(1);
        assertThat(eventCount("AI_VERIFICATION_RESUMED")).isEqualTo(1);
    }

    @Test
    void concurrentResumeExtendsDeadlineExactlyOnce() {
        var first = current(); var second = current();
        assertThat(availability.pause(first, now.minusSeconds(90), "test", "temporary failure")).isTrue();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> availability.resume(first, now, "first"));
            var b = executor.submit(() -> availability.resume(second, now, "second"));
            try { assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
        assertThat(current().getAiUnavailableSeconds()).isEqualTo(90);
        assertThat(current().getDeadlineStartedAt()).isEqualTo(now.minusHours(4).plusSeconds(90));
        assertThat(eventCount("AI_VERIFICATION_RESUMED")).isEqualTo(1);
    }

    @Test
    void timingAndEventRollBackTogetherWhenEventPersistenceFails() {
        var failedEvents = mock(ManagerReportReviewEventRepository.class);
        when(failedEvents.save(any())).thenThrow(new IllegalStateException("synthetic audit failure"));
        var service = advised(new ManagerReportReviewAiAvailabilityService(sessions, failedEvents, access));
        assertThatThrownBy(() -> service.pause(current(), now, "test", "temporary failure"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(current().getAiUnavailableStartedAt()).isNull();
        assertThat(current().getAnswersJson()).isEqualTo("[\"old\"]");
        assertThat(eventCount("AI_VERIFICATION_PAUSED")).isZero();
    }

    @Test
    void concurrentCheckInsStartOneDeadlineAndOneEventWithoutWritingAnswers() {
        jdbc.update("UPDATE manager_report_review_sessions SET deadline_started_at=NULL WHERE review_id=?", id);
        var checkIn = advised(new ManagerReportReviewCheckInService(sessions, events, access));
        var user = User.builder().id(17L).username("fixture").build();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> checkIn.checkIn(user));
            var b = executor.submit(() -> checkIn.checkIn(user));
            try { a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
        assertThat(current().getDeadlineStartedAt()).isNotNull();
        assertThat(current().getAnswersJson()).isEqualTo("[\"old\"]");
        assertThat(eventCount("DEADLINE_STARTED")).isEqualTo(1);
    }

    @Test
    void pausedAuditIsNotRemindedOrRestrictedAndExistingAnswerIsPreserved() {
        assertThat(availability.pause(current(), now.minusMinutes(1), "test", "temporary failure")).isTrue();
        var delivery = mock(NotificationMediaDeliveryService.class);
        reminder(delivery).remind();
        verifyNoInteractions(delivery);
        assertThat(current().getRestrictedAt()).isNull();
        assertThat(current().getReminderOneSentAt()).isNull();
        assertThat(current().getReminderThreeSentAt()).isNull();
        assertThat(current().getAnswersJson()).isEqualTo("[\"old\"]");
    }

    @Test
    void restrictionAndReminderOnlyUpdateMetadataEvenWhenDeliveryFails() {
        var delivery = mock(NotificationMediaDeliveryService.class);
        reminder(delivery).remind();
        assertThat(current().getRestrictedAt()).isNotNull();
        assertThat(current().getReminderThreeSentAt()).isNull();
        assertThat(current().getAnswersJson()).isEqualTo("[\"old\"]");
        assertThat(eventCount("RESTRICTION_APPLIED")).isEqualTo(1);
        reminder(delivery).remind();
        assertThat(eventCount("RESTRICTION_APPLIED")).isEqualTo(1);
    }

    @Test
    void completedAndPausedSessionsRejectLateMetadataClaims() {
        tx.executeWithoutResult(status -> {
            var review = sessions.findForUpdateById(id).orElseThrow();
            review.setStatus(ManagerReportReviewStatus.COMPLETED); review.setCompletedAt(now); em.flush();
            assertThat(sessions.pauseIfActive(id, now)).isZero();
            assertThat(sessions.startDeadlineIfAbsent(id, now)).isZero();
            assertThat(sessions.restrictIfDue(id, now, now.minusHours(3))).isZero();
            assertThat(sessions.markReminderSentIfDue(id, now, now.minusHours(3), true)).isZero();
        });
        assertThat(current().getAnswersJson()).isEqualTo("[\"old\"]");
    }

    @Test
    void recoveryClaimIsExclusiveDurableAndDoesNotChangeAnswersOrDeadline() {
        availability.pause(current(), now.minusMinutes(2), "test", "timeout");
        assertThat(sessions.findRecoveryCandidates(now, now.minusMinutes(1))).containsExactly(id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> tx.execute(ignored -> sessions.claimRecovery(
                    id, now, now.minusMinutes(1), now.plusMinutes(10))));
            var b = executor.submit(() -> tx.execute(ignored -> sessions.claimRecovery(
                    id, now, now.minusMinutes(1), now.plusMinutes(10))));
            try { assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1, 0); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
        assertThat(current().getRecoveryAttempts()).isEqualTo(1);
        assertThat(current().getRecoveryNextAttemptAt()).isEqualTo(now.plusMinutes(10));
        assertThat(current().getAnswersJson()).isEqualTo("[\"old\"]");
        assertThat(current().getDeadlineStartedAt()).isEqualTo(now.minusHours(4));
        assertThat(sessions.findRecoveryCandidates(now, now.minusMinutes(1))).isEmpty();
        assertThat(sessions.findRecoveryCandidates(now.plusMinutes(11), now)).containsExactly(id);
        availability.resume(current(), now, "test");
        assertThat(current().getRecoveryAttempts()).isZero();
        assertThat(current().getRecoveryNextAttemptAt()).isNull();
    }

    @Test
    void failedRecoveryRetainsCommittedLeaseAndDoesNotRetryOnEverySchedulerTick() {
        availability.pause(current(), now.minusMinutes(2), "test", "timeout");
        var reviews = mock(ManagerReportReviewTelegramService.class);
        doThrow(new IllegalStateException("synthetic provider transaction failure"))
                .when(reviews).recoverAutomatically(id);
        when(reviews.enabled()).thenReturn(true);

        new ManagerReportReviewRecoveryJob(sessions, reviews, transactions).recover();
        new ManagerReportReviewRecoveryJob(sessions, reviews, transactions).recover();

        verify(reviews).recoverAutomatically(id);
        assertThat(current().getRecoveryAttempts()).isEqualTo(1);
        assertThat(current().getRecoveryNextAttemptAt()).isAfter(now.plusMinutes(9));
        assertThat(current().getAiUnavailableStartedAt()).isNotNull();
        assertThat(current().getRestrictedAt()).isNull();
        assertThat(current().getAnswersJson()).isEqualTo("[\"old\"]");
    }

    @Test
    void lateRecoveryClaimCannotReopenACompletedOrTestAudit() {
        availability.pause(current(), now.minusMinutes(2), "test", "timeout");
        jdbc.update("UPDATE manager_report_review_sessions SET test_mode=1 WHERE review_id=?", id);
        assertThat(sessions.findRecoveryCandidates(now, now.minusMinutes(1))).isEmpty();
        assertThat(tx.<Integer>execute(ignored -> sessions.claimRecovery(id, now, now.minusMinutes(1), now.plusMinutes(10)))).isZero();
        jdbc.update("UPDATE manager_report_review_sessions SET test_mode=0,status='COMPLETED',completed_at=? WHERE review_id=?", now, id);
        assertThat(sessions.findRecoveryCandidates(now, now.minusMinutes(1))).isEmpty();
        assertThat(tx.<Integer>execute(ignored -> sessions.claimRecovery(id, now, now.minusMinutes(1), now.plusMinutes(10)))).isZero();
    }

    @Test
    void deliveredContextQuestionWaitsForNewAnswerWithoutRepeatedClaimsAndRemainsUnrestricted() {
        availability.pause(current(), now.minusMinutes(2), "test", "missing source");
        jdbc.update("UPDATE manager_report_review_sessions SET recovery_awaiting_context=1 WHERE review_id=?", id);
        assertThat(sessions.findRecoveryCandidates(now, now.minusMinutes(1))).isEmpty();
        assertThat(tx.<Integer>execute(ignored -> sessions.claimRecovery(id, now, now.minusMinutes(1), now.plusMinutes(10)))).isZero();
        reminder(mock(NotificationMediaDeliveryService.class)).remind();
        assertThat(current().getRestrictedAt()).isNull();
        availability.resume(current(), now, "new-answer");
        assertThat(current().isRecoveryAwaitingContext()).isFalse();
    }

    private ManagerReportReviewReminderJob reminder(NotificationMediaDeliveryService delivery) {
        var settings = mock(AppSettingService.class);
        when(settings.getBoolean("manager.report-review.enabled", true)).thenReturn(true);
        when(settings.getInt("manager.report-review.reminder-one-minutes", 60)).thenReturn(60);
        when(settings.getInt("manager.report-review.reminder-three-minutes", 180)).thenReturn(180);
        var quality = mock(ManagerReportReviewQualityService.class); when(quality.aiAvailable()).thenReturn(true);
        return new ManagerReportReviewReminderJob(sessions, events, mock(TelegramService.class), delivery,
                settings, access, quality, availability, mock(ManagerReportReviewRolloutPolicy.class), transactions);
    }

    private ManagerReportReviewSession current() { return tx.execute(status -> sessions.findById(id).orElseThrow()); }
    private long eventCount(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM manager_report_review_events WHERE review_id=? AND event_type=?",
                Long.class, id, type);
    }
    private static void independently(Runnable action) {
        try (var executor = Executors.newSingleThreadExecutor()) {
            try { executor.submit(action).get(10, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
    }
    @SuppressWarnings("unchecked")
    private static <T> T advised(T target) {
        var advice = new TransactionInterceptor(); advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(advice);
        return (T) proxy.getProxy();
    }
}
