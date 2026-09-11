package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.config.*;
import com.hunt.otziv.external_review_checks.model.*;
import com.hunt.otziv.external_review_checks.repository.ReviewExternalCheckRepository;
import com.hunt.otziv.performers.service.PerformerAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Actual production claim/release state machine and Spring proxies over independent InnoDB
 * transactions; narrow JDBC repository adapters leave unrelated entity graphs outside this test. */
@Testcontainers
class ExternalReviewAdmissionMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("worker_admission").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc; MockRestServiceServer ready,send; ExternalReviewCheckService service;
    ReviewExternalCheckRepository repository; ExternalReviewCheckTransactionService transactions;
    @BeforeEach void prepare() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds);jdbc.execute("DROP TABLE IF EXISTS fixture_checks");
        jdbc.execute("CREATE TABLE fixture_checks(id BIGINT PRIMARY KEY,status VARCHAR(24),attempts INT,token VARCHAR(80),lease_at DATETIME(6),due_at DATETIME(6),error_code VARCHAR(128)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO fixture_checks VALUES(42,'PENDING',2,NULL,NULL,TIMESTAMPADD(HOUR,-1,NOW(6)),NULL)");
        repository=mock(ReviewExternalCheckRepository.class);
        when(repository.findById(42L)).thenAnswer(x->read(null,false));
        when(repository.findByIdForProcessing(42L)).thenAnswer(x->read(null,false));
        when(repository.findClaimedForUpdate(eq(42L),anyString())).thenAnswer(x->read(x.getArgument(1),true));
        when(repository.tryClaim(eq(42L),anyString(),anyInt(),anyInt(),anyString(),anyString(),any(),any())).thenAnswer(x->
            jdbc.update("UPDATE fixture_checks SET status='CHECKING',attempts=attempts+1,token=?,lease_at=? WHERE id=42 AND status=? AND attempts=? AND attempts<?",
                x.getArgument(4),x.getArgument(7),x.getArgument(1),x.getArgument(2),x.getArgument(3)));
        when(repository.save(any(ReviewExternalCheck.class))).thenAnswer(x->{ReviewExternalCheck c=x.getArgument(0);
            jdbc.update("UPDATE fixture_checks SET status=?,attempts=?,token=?,lease_at=?,due_at=?,error_code=? WHERE id=42",c.getStatus().name(),c.getAttemptCount(),c.getProcessingToken(),c.getProcessingLeaseUntil(),c.getCheckAfter(),c.getErrorMessage());return c;});
        var properties=new ExternalReviewCheckProperties();properties.setEnabled(true);properties.setWorkerBaseUrl("http://worker.test");
        var runtime=mock(ExternalReviewCheckRuntimeSwitch.class);when(runtime.isEnabled()).thenReturn(true);
        var performer=mock(PerformerAssignmentService.class);when(performer.textForExternalCheck(any())).thenReturn("fixture review");
        var reviews=mock(ReviewRepository.class);var aggregate=new Review();aggregate.setId(7L);
        when(reviews.findBaseByIdForExternalCheckUpdate(7L)).thenReturn(Optional.of(aggregate));
        var actual=new ExternalReviewCheckTransactionService(repository,reviews,performer,properties,runtime);
        var factory=new ProxyFactory(actual);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        transactions=(ExternalReviewCheckTransactionService)factory.getProxy();
        var config=new ExternalReviewWorkerHttpConfig();var http=config.externalReviewWorkerRestTemplate(properties);var readiness=config.externalReviewWorkerReadinessRestTemplate(properties);
        ready=MockRestServiceServer.bindTo(readiness).build();send=MockRestServiceServer.bindTo(http).build();
        var client=new ExternalReviewWorkerClient(http,readiness,properties,runtime);
        service=new ExternalReviewCheckService(repository,client,mock(ExternalReviewScreenshotStorage.class),properties,runtime,transactions);
    }
    Optional<ReviewExternalCheck> read(String token,boolean lock) {
        return jdbc.query("SELECT * FROM fixture_checks WHERE id=42"+(token==null?"":" AND token=?")+(lock?" FOR UPDATE":""),(rs,row)->{
            var c=new ReviewExternalCheck();c.setId(42L);c.setStatus(ExternalReviewCheckStatus.valueOf(rs.getString("status")));c.setAttemptCount(rs.getInt("attempts"));
            c.setProcessingToken(rs.getString("token"));var lease=rs.getTimestamp("lease_at");c.setProcessingLeaseUntil(lease==null?null:lease.toLocalDateTime());
            c.setCheckAfter(rs.getTimestamp("due_at").toLocalDateTime());c.setErrorMessage(rs.getString("error_code"));
            var review=new Review();review.setId(7L);c.setReview(review);c.setPlatform(ExternalReviewCheckPlatform.YANDEX);c.setFilialUrl("https://example.test/fixture");return c;
        },token==null?new Object[0]:new Object[]{token}).stream().findFirst();
    }
    void ready() {ready.expect(requestTo("http://worker.test/ready")).andRespond(withSuccess("{\"ok\":true,\"state\":\"ready\"}",MediaType.APPLICATION_JSON));}
    @Test void notReadyDoesNotAcquireClaimOrSpendAttempt() {
        ready.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(service.processOne(42L)).isFalse();verifyNoInteractions(repository);assertThat(read(null,false).orElseThrow().getAttemptCount()).isEqualTo(2);send.verify();ready.verify();
    }
    @Test void refusalAfterSuccessfulProbeRestoresPersistedAttemptAndQueueState() {
        ready();send.expect(anything()).andRespond(request->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT attempts FROM fixture_checks WHERE id=42",Integer.class)).isEqualTo(3);
            return withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("X-Otziv-Admission","rejected-v1").body("{\"status\":\"ERROR\",\"code\":\"draining\"}").contentType(MediaType.APPLICATION_JSON).createResponse(request);});
        assertThat(service.processOne(42L)).isFalse();var row=read(null,false).orElseThrow();
        assertThat(row.getAttemptCount()).isEqualTo(2);assertThat(row.getStatus()).isEqualTo(ExternalReviewCheckStatus.PENDING);assertThat(row.getProcessingToken()).isNull();
        ready.verify();send.verify();
    }
    @Test void plainProxy503IsNotEvidenceOfUnconsumedWork() {
        ready();send.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("{\"status\":\"ERROR\",\"code\":\"draining\"}"));
        assertThat(service.processOne(42L)).isTrue();var row=read(null,false).orElseThrow();assertThat(row.getAttemptCount()).isEqualTo(3);assertThat(row.getStatus()).isEqualTo(ExternalReviewCheckStatus.ERROR);
    }
    @Test void lateRefusalCannotUndoAnotherClaimToken() {
        ready();send.expect(anything()).andRespond(request->{jdbc.update("UPDATE fixture_checks SET token='new-fixture-token',attempts=4 WHERE id=42");
            return withStatus(HttpStatus.TOO_MANY_REQUESTS).header("X-Otziv-Admission","rejected-v1").body("{\"status\":\"ERROR\",\"code\":\"worker_busy\"}").contentType(MediaType.APPLICATION_JSON).createResponse(request);});
        assertThat(service.processOne(42L)).isFalse();var row=read(null,false).orElseThrow();assertThat(row.getAttemptCount()).isEqualTo(4);assertThat(row.getProcessingToken()).isEqualTo("new-fixture-token");
    }
    @Test void leadDueProjectionUsesDueTimeAndExcludesFutureRetriesAndTerminalHistory() {
        jdbc.execute("DROP TABLE IF EXISTS lead_command_queue");
        jdbc.execute("CREATE TABLE lead_command_queue(id BIGINT PRIMARY KEY,delivery_state VARCHAR(24),next_attempt_at DATETIME(6),created_at DATETIME(6),INDEX due(delivery_state,next_attempt_at)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO lead_command_queue VALUES(1,'READY',TIMESTAMPADD(SECOND,-120,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-30,UTC_TIMESTAMP(6))),(2,'READY',TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6)),(3,'SUCCEEDED',TIMESTAMPADD(DAY,-90,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6))");
        var value=new com.hunt.otziv.l_lead.repository.LeadCommandRepository(jdbc).dueHealth();
        assertThat(value.count()).isEqualTo(1);assertThat(value.oldestSeconds()).isBetween(120L,125L);
    }
}
