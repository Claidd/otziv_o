package com.hunt.otziv.worker_activity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.personal_reminders.api.SystemReminderCommands;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.t_telegrambot.service.TelegramNotificationService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerRiskReviewerDirectoryService;
import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentLevel;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskEventRepository;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Real production mappings and transaction boundaries; external delivery/AI remain mocked. */
@Testcontainers
class WorkerRiskTelegramCallbackTransactionMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("worker_risk_explanation_transaction")
            .withUsername("root")
            .withPassword("root");

    private static LocalContainerEntityManagerFactoryBean factoryBean;
    private static EntityManagerFactory entityManagerFactory;
    private static JpaTransactionManager transactionManager;
    private static EntityManager entityManager;
    private static JdbcTemplate committedJdbc;
    private WorkerRiskTelegramCallbackService service;
    private UserService userService;
    private PersonalReminderService reminders;
    private TelegramService telegram;
    private final List<EntityManager> workerLookupContexts = new ArrayList<>();
    private Long workerId;
    private Long managerUserId;
    private Long incidentId;
    private static final long WORKER_CHAT = 888L;
    private static final long MANAGER_CHAT = 999L;
    private static final String EXPLANATION = "Аккаунт был заблокирован при проверке, поэтому деактивировала его.";

    @BeforeAll
    static void startHibernate() {
        DriverManagerDataSource dataSource = dataSource();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        // Production entity scanning also discovers unrelated encrypted fields.
        // Their converter needs constructor injection, but no encrypted data is used here.
        beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factoryBean.setPackagesToScan("com.hunt.otziv");
        factoryBean.setJpaPropertyMap(Map.of(
                // The disposable Testcontainer is always empty and owns cleanup.
                "hibernate.hbm2ddl.auto", "create-only",
                "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans)));
        factoryBean.afterPropertiesSet();
        entityManagerFactory = factoryBean.getObject();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactionManager = new JpaTransactionManager(entityManagerFactory);
        // A distinct DataSource object prevents JdbcTemplate joining the Hibernate
        // transaction: these assertions see only rows another connection can read.
        committedJdbc = new JdbcTemplate(dataSource());
        committedJdbc.setQueryTimeout(10);
    }

    @AfterAll
    static void stopHibernate() {
        if (factoryBean != null) {
            factoryBean.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        for (String table : List.of("worker_risk_events", "worker_risk_incidents", "managers_users",
                "users_roles", "managers", "users")) {
            committedJdbc.update("DELETE FROM " + table);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User managerUser = User.builder().username("risk-manager").active(true)
                    .telegramChatId(MANAGER_CHAT).build();
            entityManager.persist(managerUser);
            managerUserId = managerUser.getId();
            Manager manager = Manager.builder().user(managerUser).build();
            entityManager.persist(manager);
            User worker = User.builder().username("risk-worker").active(true)
                    .telegramChatId(WORKER_CHAT).workerTelegramGroupChatId(-100123L)
                    .managers(new LinkedHashSet<>(List.of(manager))).build();
            entityManager.persist(worker);
            workerId = worker.getId();
            WorkerRiskIncident incident = new WorkerRiskIncident();
            incident.setWorkerUserId(workerId);
            incident.setWorkerUsername("risk-worker");
            incident.setLevel(WorkerRiskIncidentLevel.HIGH_RISK);
            incident.setRuleCode("RISK179_REGRESSION");
            incident.setTitle("Проверка причины блокировки аккаунта");
            incident.setOrderId(100L);
            incident.setReviewId(200L);
            incident.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
            incident.setExplanationRequestedAt(LocalDateTime.now().minusHours(4));
            incident.setExplanationPromptedAt(LocalDateTime.now().minusHours(4));
            incident.setResponseDueAt(LocalDateTime.now().minusHours(1));
            incident.setSectionRestrictedAt(LocalDateTime.now().minusMinutes(30));
            incident.setTelegramNotificationChatId(MANAGER_CHAT);
            incident.setTelegramNotificationMessageId(10);
            entityManager.persist(incident);
            incidentId = incident.getId();
        });

        userService = mock(UserService.class);
        reminders = mock(PersonalReminderService.class);
        telegram = mock(TelegramService.class);
        when(userService.findByIdToUserInfo(anyLong())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (workerId.equals(invocation.getArgument(0, Long.class))) {
                workerLookupContexts.add(org.springframework.orm.jpa.EntityManagerFactoryUtils
                        .getTransactionalEntityManager(entityManagerFactory));
            }
            return entityManager.find(User.class, invocation.getArgument(0, Long.class));
        });
        when(userService.getAllOwners(any())).thenReturn(List.of());
        // Execute the real scalar reminder API while keeping its existing persistence
        // collaborators mocked, as before. The recipient is resolved in REQUIRES_NEW.
        ReflectionTestUtils.setField(reminders, "userService", userService);
        doCallRealMethod().when(reminders).ensureOpenDueNow(any(SystemReminderCommands.Reminder.class));
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
        WorkerRiskIncidentRepository incidents = repositories.getRepository(WorkerRiskIncidentRepository.class);
        WorkerRiskEventService events = transactionalProxy(new WorkerRiskEventService(
                repositories.getRepository(WorkerRiskEventRepository.class), new ObjectMapper()));
        WorkerRiskExplanationQualityService assessment = mock(WorkerRiskExplanationQualityService.class);
        when(assessment.assess(any(), any())).thenReturn(new WorkerRiskExplanationQualityService.Result(
                WorkerRiskExplanationQuality.LOGICAL, BigDecimal.ONE, "Ответ по существу", "",
                "test", "test", 1, 1));
        WorkerRiskExplanationNotificationService notifications = transactionalProxy(
                new WorkerRiskExplanationNotificationService(
                        transactionalProxy(new WorkerRiskReviewerDirectoryService(userService)),
                        reminders, new TelegramNotificationService(telegram)));
        service = transactionalProxy(new WorkerRiskTelegramCallbackService(
                incidents, mock(GamificationScoreLedgerRepository.class), userService, reminders, telegram,
                mock(ManagerDailyControlConcreteItemRepository.class), mock(OrderRepository.class),
                assessment, events, mock(AppSettingService.class), new WorkerRiskDecisionPolicy(), notifications));
    }

    @Test
    void detachedWorkerWithUninitializedManagersCommitsBeforeGreenAndNotifiesInNewPersistenceContext() {
        User detached = detachedWorker();
        doAnswer(invocation -> {
            assertAcceptedDurably();
            return true;
        }).when(telegram).sendMessage(eq(WORKER_CHAT), contains("🟢 ОТВЕТ ПОЛУЧЕН"));

        assertThat(service.handleWorkerTextMessage(WORKER_CHAT, detached, EXPLANATION)).isTrue();

        assertAcceptedDurably();
        verify(telegram).sendMessage(eq(WORKER_CHAT), contains("🟢 ОТВЕТ ПОЛУЧЕН"));
        verify(telegram).sendMessage(eq(MANAGER_CHAT), contains(EXPLANATION));
        verify(reminders).createSystemReminderDueNow(any(User.class), eq("Получено пояснение специалиста"),
                contains(EXPLANATION), eq("WORKER_RISK_WORKER_EXPLANATION"), eq(incidentId), eq(100L));
        assertThat(workerLookupContexts).hasSize(2);
        assertThat(workerLookupContexts.get(0)).isNotSameAs(workerLookupContexts.get(1));
        assertThat(Hibernate.isInitialized(detached.getManagers())).isFalse();
    }

    @Test
    void failureAtOuterCommitRollsBackExplanationReleaseAndEventsWithoutAnyTelegramSuccess() {
        User detached = detachedWorker();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(service.handleWorkerTextMessage(WORKER_CHAT, detached, EXPLANATION)).isTrue();
            entityManager.flush();
            verifyNoInteractions(telegram);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("forced late commit failure");
                }
            });
        })).isInstanceOf(IllegalStateException.class).hasMessage("forced late commit failure");

        assertPendingDurably();
        verifyNoInteractions(telegram);
        assertThat(workerLookupContexts).hasSize(1);
    }

    @Test
    void telegramFailureAfterCommitDoesNotUndoAcceptedExplanationOrSuppressReviewers() {
        doAnswer(invocation -> {
            assertAcceptedDurably();
            throw new IllegalStateException("simulated Telegram failure");
        }).when(telegram).sendMessage(eq(WORKER_CHAT), contains("🟢 ОТВЕТ ПОЛУЧЕН"));

        assertThat(service.handleWorkerTextMessage(WORKER_CHAT, detachedWorker(), EXPLANATION)).isTrue();

        assertAcceptedDurably();
        verify(telegram).sendMessage(eq(MANAGER_CHAT), contains(EXPLANATION));
    }

    @Test
    void reviewerTransactionRollbackAfterCommitCannotUndoAcceptedExplanation() {
        when(userService.getAllOwners("ROLE_OWNER")).thenAnswer(invocation -> {
            assertAcceptedDurably();
            // Emulate a caught repository failure poisoning only REQUIRES_NEW.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new IllegalStateException("simulated recipient lookup failure");
        });

        assertThat(service.handleWorkerTextMessage(WORKER_CHAT, detachedWorker(), EXPLANATION)).isTrue();

        assertAcceptedDurably();
        verify(telegram).sendMessage(eq(WORKER_CHAT), contains("🟢 ОТВЕТ ПОЛУЧЕН"));
        assertThat(workerLookupContexts).hasSize(2);
        assertThat(workerLookupContexts.get(0)).isNotSameAs(workerLookupContexts.get(1));
    }

    @Test
    void failureCompletingReviewerTransactionCannotEscapeOrUndoCommittedExplanation() {
        when(userService.getAllOwners("ROLE_OWNER")).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    assertAcceptedDurably();
                    throw new IllegalStateException("simulated reviewer transaction commit failure");
                }
            });
            return List.of();
        });

        assertThat(service.handleWorkerTextMessage(WORKER_CHAT, detachedWorker(), EXPLANATION)).isTrue();

        assertAcceptedDurably();
        verify(telegram).sendMessage(eq(WORKER_CHAT), contains("🟢 ОТВЕТ ПОЛУЧЕН"));
        assertThat(workerLookupContexts).hasSize(2);
        assertThat(workerLookupContexts.get(0)).isNotSameAs(workerLookupContexts.get(1));
    }

    private User detachedWorker() {
        User detached;
        try (EntityManager loader = entityManagerFactory.createEntityManager()) {
            detached = loader.find(User.class, workerId);
            assertThat(Hibernate.isInitialized(detached.getManagers())).isFalse();
        }
        // Establish the exact production precondition, using Hibernate's real collection.
        assertThatThrownBy(() -> detached.getManagers().size()).isInstanceOf(LazyInitializationException.class);
        return detached;
    }

    private void assertAcceptedDurably() {
        Map<String, Object> row = committedJdbc.queryForMap("""
                SELECT worker_explanation, explanation_accepted_at, section_restriction_released_at,
                       explanation_attempt_count FROM worker_risk_incidents WHERE incident_id = ?
                """, incidentId);
        assertThat(row.get("worker_explanation")).isEqualTo(EXPLANATION);
        assertThat(row.get("explanation_accepted_at")).isNotNull();
        assertThat(row.get("section_restriction_released_at")).isNotNull();
        assertThat(((Number) row.get("explanation_attempt_count")).intValue()).isEqualTo(1);
        assertThat(committedJdbc.queryForList(
                "SELECT event_type FROM worker_risk_events WHERE incident_id = ?", String.class, incidentId))
                .containsExactlyInAnyOrder("EXPLANATION_RECEIVED", "EXPLANATION_ASSESSED", "SPECIALIST_SECTION_RELEASED");
    }

    private void assertPendingDurably() {
        Map<String, Object> row = committedJdbc.queryForMap("""
                SELECT worker_explanation, explanation_accepted_at, section_restriction_released_at,
                       explanation_attempt_count FROM worker_risk_incidents WHERE incident_id = ?
                """, incidentId);
        assertThat(row.get("worker_explanation")).isNull();
        assertThat(row.get("explanation_accepted_at")).isNull();
        assertThat(row.get("section_restriction_released_at")).isNull();
        assertThat(((Number) row.get("explanation_attempt_count")).intValue()).isZero();
        assertThat(committedJdbc.queryForObject(
                "SELECT COUNT(*) FROM worker_risk_events WHERE incident_id = ?", Long.class, incidentId)).isZero();
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactionalProxy(T target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }
}
