package com.hunt.otziv.worker_activity.account_action;

import com.hunt.otziv.business_audit.model.BusinessAuditEvent;
import com.hunt.otziv.business_audit.repository.BusinessAuditEventRepository;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import java.time.LocalDateTime;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class WorkerAccountCredentialGuardMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("credential_guard").withUsername("root").withPassword("root");

    @Test
    void durableAuditScopesAndAssignmentBoundaryMatchTheRealIncident() {
        try (var factory = new Configuration()
                .addAnnotatedClass(BusinessAuditEvent.class).addAnnotatedClass(WorkerActivityEvent.class)
                .setProperty("hibernate.connection.url", MYSQL.getJdbcUrl())
                .setProperty("hibernate.connection.username", MYSQL.getUsername())
                .setProperty("hibernate.connection.password", MYSQL.getPassword())
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
             var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            LocalDateTime at = LocalDateTime.of(2026, 9, 18, 13, 21, 54);
            session.persist(change(at.minusMinutes(19)));
            session.persist(reveal("login", at.minusMinutes(18)));
            session.persist(reveal("password", at.minusMinutes(18).plusSeconds(8)));
            // Evaluation sees the just-committed block, but must use the preceding assignment.
            var currentBlock = change(at);
            session.persist(currentBlock);
            session.flush();
            var repositories = new JpaRepositoryFactory(session);
            var guard = new WorkerAccountCredentialGuard(
                    repositories.getRepository(BusinessAuditEventRepository.class),
                    repositories.getRepository(WorkerActivityEventRepository.class));
            assertTrue(guard.hasBothCredentials("maks", "review", 197623L, 871819L, at.plusNanos(100), currentBlock.getId()));
            assertFalse(guard.hasBothCredentials("other", "review", 197623L, 871819L, at.plusNanos(100), currentBlock.getId()));
            assertFalse(guard.hasBothCredentials("maks", "recovery_task", 197623L, 871819L, at.plusNanos(100), currentBlock.getId()));
            assertFalse(guard.hasBothCredentials("maks", "review", 197623L, 87181L, at.plusNanos(100), currentBlock.getId()));
            assertFalse(guard.hasBothCredentials("maks", "review", 197624L, 871819L, at.plusNanos(100), currentBlock.getId()));
            assertFalse(guard.hasBothCredentials("maks", "review", 197623L, 871819L, at.plusMinutes(1)));
            transaction.rollback();
        }
    }

    private BusinessAuditEvent reveal(String field, LocalDateTime at) {
        var event = new BusinessAuditEvent();
        event.setActor("Maks"); event.setSource("test"); event.setAction("CREDENTIAL_REVEAL");
        event.setEntityType("review"); event.setEntityId("197623"); event.setReviewId(197623L);
        event.setCreatedAt(at); event.setDetails("field=" + field + ";botId=871819;sourceSection=nagul;");
        return event;
    }

    private WorkerActivityEvent change(LocalDateTime at) {
        var event = new WorkerActivityEvent();
        event.setWorkerUserId(15L); event.setWorkerUsername("Maks");
        event.setAction(WorkerActivityAction.REVIEW_BOT_DEACTIVATE);
        event.setEntityType("review"); event.setEntityId(197623L); event.setReviewId(197623L);
        event.setCreatedAt(at);
        return event;
    }
}
