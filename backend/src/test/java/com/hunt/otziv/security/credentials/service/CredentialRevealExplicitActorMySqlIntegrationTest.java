package com.hunt.otziv.security.credentials.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.business_audit.model.BusinessAuditEvent;
import com.hunt.otziv.business_audit.repository.BusinessAuditEventRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.security.credentials.CredentialRevealRequest;
import com.hunt.otziv.security.credentials.CredentialRevealResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Actual CredentialRevealService -> BusinessAuditService -> Spring REQUIRES_NEW -> InnoDB writes.
 * A JDBC-backed audit repository adapter isolates actor/commit ordering; this does not replace
 * Hibernate mapping or credential-encryption acceptance. Commit failure kills a real MySQL session.
 */
@Testcontainers
class CredentialRevealExplicitActorMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("credential_explicit_actor").withUsername("root").withPassword(UUID.randomUUID().toString());
    private static final Authentication CAPTURED = actor("captured-a");
    private final AtomicReference<Long> auditConnection = new AtomicReference<>();
    private final AtomicBoolean failCommit = new AtomicBoolean();
    private final AtomicBoolean killedBeforeCommit = new AtomicBoolean();
    private final AtomicBoolean failInsert = new AtomicBoolean();
    private final AtomicInteger saveAttempts = new AtomicInteger();
    private JdbcTemplate jdbc;
    private JdbcTemplate independent;
    private DataSourceTransactionManager transactions;
    private BusinessAuditEventRepository auditRepository;
    private CredentialRevealService service;
    private Review review;
    private BadReviewTask badTask;
    private ReviewRecoveryTask recoveryTask;
    private String currentLogin, currentPassword, snapshotLogin, snapshotPassword;

    @BeforeEach void setUp() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        independent = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        transactions = new DataSourceTransactionManager(dataSource);
        failCommit.set(false); killedBeforeCommit.set(false); failInsert.set(false); saveAttempts.set(0); auditConnection.set(null);
        jdbc.execute("DROP TABLE IF EXISTS reveal_audit_fixture,reveal_caller_fixture");
        jdbc.execute("CREATE TABLE reveal_audit_fixture(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor VARCHAR(150),source VARCHAR(80),action VARCHAR(80),entity_type VARCHAR(40),entity_id VARCHAR(80),order_id BIGINT,review_id BIGINT,old_value TEXT,new_value TEXT,details TEXT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE reveal_caller_fixture(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        auditRepository = mock(BusinessAuditEventRepository.class);
        when(auditRepository.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            saveAttempts.incrementAndGet();
            long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            auditConnection.set(connection);
            BusinessAuditEvent event = call.getArgument(0);
            if (failInsert.get()) jdbc.execute("INSERT INTO deliberately_missing_reveal_audit_table VALUES (1)");
            jdbc.update("INSERT INTO reveal_audit_fixture(actor,source,action,entity_type,entity_id,order_id,review_id,old_value,new_value,details) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    event.getActor(), event.getSource(), event.getAction(), event.getEntityType(), event.getEntityId(),
                    event.getOrderId(), event.getReviewId(), event.getOldValue(), event.getNewValue(), event.getDetails());
            assertThat(independent.queryForObject("SELECT COUNT(*) FROM reveal_audit_fixture", Integer.class)).isZero();
            if (failCommit.get()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reveal_audit_fixture", Integer.class)).isEqualTo(1);
                        // A real server-side disconnect rolls back the INSERT; the subsequent JDBC commit must fail.
                        independent.execute("KILL CONNECTION " + connection);
                        killedBeforeCommit.set(true);
                    }
                });
            }
            return event;
        });
        service = new CredentialRevealService(new BusinessAuditService(auditRepository, transactions));
        currentLogin = "fixture-login-" + UUID.randomUUID();
        currentPassword = UUID.randomUUID().toString();
        snapshotLogin = "fixture-snapshot-" + UUID.randomUUID();
        snapshotPassword = UUID.randomUUID().toString();
        var bot = Bot.builder().id(142L).login(currentLogin).password(currentPassword).build();
        var order = new Order(); order.setId(77L);
        var detail = new OrderDetails(); detail.setOrder(order);
        review = new Review(); review.setId(42L); review.setBot(bot); review.setOrderDetails(detail);
        badTask = BadReviewTask.builder().id(51L).sourceReview(review).bot(bot).order(order)
                .botLoginSnapshot(snapshotLogin).botPasswordSnapshot(snapshotPassword).build();
        recoveryTask = ReviewRecoveryTask.builder().id(61L).sourceReview(review).bot(bot).order(order)
                .botLoginSnapshot(snapshotLogin).botPasswordSnapshot(snapshotPassword).build();
        SecurityContextHolder.getContext().setAuthentication(actor("ambient-b"));
        var request = new MockHttpServletRequest(); request.setRequestURI("/api/worker/fixture/reveal");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }

    @ParameterizedTest
    @MethodSource("successfulPaths")
    void eachExplicitRevealCommitsTheCapturedActorBeforeReturningAndNeverAuditsCredential(Path path, String field, boolean emptyAmbient) {
        if (emptyAmbient) SecurityContextHolder.clearContext();
        CredentialRevealResponse response = reveal(path, field, CAPTURED);
        assertThat(response.value()).isEqualTo(expected(path, field));
        assertThat(saveAttempts.get()).isEqualTo(1);
        var event = independent.queryForMap("SELECT * FROM reveal_audit_fixture");
        assertThat(event.get("actor")).isEqualTo("captured-a");
        assertThat(event.get("source")).isEqualTo("worker_board");
        assertThat(event.get("action")).isEqualTo("CREDENTIAL_REVEAL");
        assertThat(event.get("entity_type")).isEqualTo(path.entityType);
        assertThat(event.get("entity_id")).isEqualTo(Long.toString(path.entityId));
        assertThat(event.get("order_id")).isEqualTo(77L);
        assertThat(event.get("review_id")).isEqualTo(42L);
        assertThat(event.get("old_value")).isNull(); assertThat(event.get("new_value")).isNull();
        assertThat((String) event.get("details")).contains("field=" + field, "botId=142", "sourcePage=fixture-page");
        assertThat(event.values().toString()).doesNotContain(currentLogin, currentPassword, snapshotLogin, snapshotPassword);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void auditRequiresNewCommitSurvivesCallerRollbackAndOriginalConnectionIsResumed(Path path) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            long caller = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            jdbc.update("INSERT INTO reveal_caller_fixture VALUES(1)");
            assertThat(reveal(path, "password", CAPTURED).value()).isEqualTo(expected(path, "password"));
            assertThat(auditConnection.get()).isNotEqualTo(caller);
            assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(caller);
            assertThat(independent.queryForObject("SELECT COUNT(*) FROM reveal_audit_fixture", Integer.class)).isEqualTo(1);
            assertThat(independent.queryForObject("SELECT COUNT(*) FROM reveal_caller_fixture", Integer.class)).isZero();
            throw new IllegalStateException("fixture caller rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("fixture caller rollback");
        assertThat(independent.queryForObject("SELECT actor FROM reveal_audit_fixture", String.class)).isEqualTo("captured-a");
        assertThat(independent.queryForObject("SELECT COUNT(*) FROM reveal_caller_fixture", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void realDatabaseCommitFailurePreventsEveryRevealResponse(Path path) {
        failCommit.set(true);
        AtomicReference<CredentialRevealResponse> disclosed = new AtomicReference<>();
        assertThatThrownBy(() -> disclosed.set(reveal(path, "password", CAPTURED))).isInstanceOf(RuntimeException.class);
        assertThat(killedBeforeCommit.get()).isTrue();
        assertThat(saveAttempts.get()).isEqualTo(1);
        assertThat(disclosed.get()).isNull();
        assertThat(independent.queryForObject("SELECT COUNT(*) FROM reveal_audit_fixture", Integer.class)).isZero();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void realAuditInsertFailurePreventsEveryRevealResponse(Path path) {
        failInsert.set(true);
        AtomicReference<CredentialRevealResponse> disclosed = new AtomicReference<>();
        assertThatThrownBy(() -> disclosed.set(reveal(path, "password", CAPTURED))).isInstanceOf(RuntimeException.class);
        assertThat(disclosed.get()).isNull();
        assertThat(saveAttempts.get()).isEqualTo(1);
        assertThat(independent.queryForObject("SELECT COUNT(*) FROM reveal_audit_fixture", Integer.class)).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void invalidExplicitActorCannotBorrowTheValidAmbientActor(Path path, Authentication invalid) {
        assertThatThrownBy(() -> reveal(path, "password", invalid)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(auditRepository);
        assertThat(independent.queryForObject("SELECT COUNT(*) FROM reveal_audit_fixture", Integer.class)).isZero();
    }

    static Stream<Arguments> successfulPaths() {
        return Stream.of(Path.values()).flatMap(path -> Stream.of("login", "password")
                .flatMap(field -> Stream.of(false, true).map(empty -> Arguments.of(path, field, empty))));
    }
    static Stream<Arguments> invalidPaths() {
        return Stream.of(Path.values()).flatMap(path -> Stream.of(
                Arguments.of(path, null),
                Arguments.of(path, new AnonymousAuthenticationToken("fixture-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))),
                Arguments.of(path, new UsernamePasswordAuthenticationToken("unauthenticated", "unused")),
                Arguments.of(path, actor(" \t "))));
    }
    private CredentialRevealResponse reveal(Path path, String field, Authentication actor) {
        var request = new CredentialRevealRequest(field, "fixture-page", "menu", "fixture-section");
        return switch (path) {
            case REVIEW -> service.revealReview(review, request, actor);
            case BAD -> service.revealBadReviewTask(badTask, request, actor);
            case RECOVERY -> service.revealRecoveryTask(recoveryTask, request, actor);
        };
    }
    private String expected(Path path, String field) {
        return path == Path.BAD ? ("login".equals(field) ? snapshotLogin : snapshotPassword)
                : ("login".equals(field) ? currentLogin : currentPassword);
    }
    private static Authentication actor(String name) {
        return new UsernamePasswordAuthenticationToken(name, "unused", List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
    }
    enum Path {
        REVIEW("review", 42L), BAD("bad_review_task", 51L), RECOVERY("recovery_task", 61L);
        final String entityType; final long entityId;
        Path(String entityType, long entityId) { this.entityType = entityType; this.entityId = entityId; }
    }
}
