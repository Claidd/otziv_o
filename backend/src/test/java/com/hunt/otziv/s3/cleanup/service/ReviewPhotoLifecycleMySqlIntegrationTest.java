package com.hunt.otziv.s3.cleanup.service;

import com.hunt.otziv.p_products.api.ReviewPhotoRecords;
import com.hunt.otziv.p_products.application.ReviewPhotoRecordService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.photo.ReviewPhotoReferencePolicy;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.s3.service.ReviewPhotoDeletionGuard;
import com.hunt.otziv.s3.service.ReviewPhotoUploadService;
import com.hunt.otziv.s3.service.S3UploadServiceImpl;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.uploads.service.FileUploadGuard;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL transactions/registry/cleanup; minimal review persistence bindings and fake S3. */
@Testcontainers
class ReviewPhotoLifecycleMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("photo_lifecycle").withUsername("root").withPassword("root");
    private static final String OLD = "https://cdn.test/reviews/17-old.jpg";
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private S3ObjectCleanupQueue cleanup;
    private S3UploadRegistry registry;
    private ReviewPhotoRecords records;
    private ReviewPhotoReferencePolicy references;
    private S3UploadServiceImpl uploads;
    private ReviewPhotoUploadService workflow;
    private S3Client s3;
    private MockMultipartFile file;
    private CyclicBarrier guardReadBarrier;
    private final Set<String> objects = ConcurrentHashMap.newKeySet();
    private final Authentication actor = new UsernamePasswordAuthenticationToken("owner", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS s3_object_cleanup_queue, s3_upload_registry, review_photo_reference_guard, reviews");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1_10_209__durable_s3_object_cleanup_queue.sql"),
                new ClassPathResource("db/migration/V1_10_308__review_photo_upload_lifecycle.sql")).execute(dataSource);
        jdbc.execute("CREATE TABLE reviews (review_id BIGINT PRIMARY KEY, review_url VARCHAR(2048), actor_name VARCHAR(50))");
        jdbc.update("INSERT INTO reviews VALUES (17, ?, 'owner')", OLD);
        transactions = new DataSourceTransactionManager(dataSource);
        var named = new NamedParameterJdbcTemplate(jdbc);
        references = new ReviewPhotoReferencePolicy(named);
        var guard = mock(WorkerAssignmentMutationGuardService.class);
        when(guard.requireReviewOrder(anyLong(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // Match the real guard's parent discovery before its pessimistic lock.
            jdbc.queryForObject("SELECT review_id FROM reviews WHERE review_id = ?",
                    Long.class, (Long) invocation.getArgument(0));
            if (guardReadBarrier != null) guardReadBarrier.await(10, TimeUnit.SECONDS);
            String owner = jdbc.queryForObject("SELECT actor_name FROM reviews WHERE review_id = ? FOR UPDATE",
                    String.class, (Long) invocation.getArgument(0));
            if (!owner.equals(((Authentication) invocation.getArgument(1)).getName())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            return 10L;
        });
        var reviewRepository = mock(ReviewRepository.class);
        when(reviewRepository.findById(anyLong())).thenAnswer(invocation -> Optional.of(Review.builder()
                .id(invocation.getArgument(0)).url(jdbc.queryForObject("SELECT review_url FROM reviews WHERE review_id = ?",
                        String.class, (Long) invocation.getArgument(0))).build()));
        when(reviewRepository.save(any())).thenAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            jdbc.update("UPDATE reviews SET review_url = ? WHERE review_id = ?", review.getUrl(), review.getId());
            return review;
        });
        records = proxy(new ReviewPhotoRecordService(guard, reviewRepository, references));
        s3 = mock(S3Client.class);
        objects.clear(); objects.add("reviews/17-old.jpg");
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            objects.add(((PutObjectRequest) invocation.getArgument(0)).key());
            return PutObjectResponse.builder().build();
        });
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            objects.remove(((DeleteObjectRequest) invocation.getArgument(0)).key());
            return DeleteObjectResponse.builder().build();
        });
        cleanup = proxy(new S3ObjectCleanupQueue(named, s3, mock(SchedulerLeaseService.class)));
        ReflectionTestUtils.setField(cleanup, "configuredBatchSize", 25);
        var deletionGuard = new ReviewPhotoDeletionGuard(records);
        ReflectionTestUtils.setField(deletionGuard, "bucket", "bucket");
        ReflectionTestUtils.setField(deletionGuard, "publicBaseUrl", "https://cdn.test");
        ReflectionTestUtils.setField(deletionGuard, "projectId", "project");
        ReflectionTestUtils.setField(cleanup, "deletionGuards", List.of(deletionGuard));
        registry = proxy(new S3UploadRegistry(named, cleanup, transactions));
        uploads = spy(new S3UploadServiceImpl(s3,
                new FileUploadGuard(5_242_880, 20_000_000, 8000, 8000, 5_242_880, 5000), cleanup, registry));
        ReflectionTestUtils.setField(uploads, "bucket", "bucket");
        ReflectionTestUtils.setField(uploads, "publicBaseUrl", "https://cdn.test");
        ReflectionTestUtils.setField(uploads, "projectId", "project");
        workflow = new ReviewPhotoUploadService(records, uploads, registry, transactions);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", bytes);
        file = new MockMultipartFile("file", "photo.png", "image/png", bytes.toByteArray());
    }

    @Test
    void replacementCommitsWithCleanupIntentBeforeAnyDelete() {
        String url = workflow.replace(17, 10L, file, actor);
        assertThat(currentUrl()).isEqualTo(url);
        assertThat(count("s3_object_cleanup_queue")).isEqualTo(1);
        assertThat(objects).hasSize(2);
        cleanup.processDueBatch();
        assertThat(objects).containsExactly(key(url));
        assertThat(count("s3_object_cleanup_queue")).isZero();
    }

    @Test
    void crashAfterPutLeavesDurableRecoveryAndPreservesPreviousPhoto() {
        doAnswer(invocation -> {
            objects.add(((PutObjectRequest) invocation.getArgument(0)).key());
            throw new SimulatedProcessDeath();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThatThrownBy(() -> workflow.replace(17, 10L, file, actor)).isInstanceOf(SimulatedProcessDeath.class);
        expireUploads(); registry.recoverUnattachedUploads(); cleanup.processDueBatch();
        assertThat(currentUrl()).isEqualTo(OLD);
        assertThat(objects).containsExactly(key(OLD));
    }

    @Test
    void crashAfterRemoteDeleteBeforeQueueAcknowledgementReplaysDeleteSafely() {
        String current = workflow.replace(17, 10L, file, actor);
        doAnswer(invocation -> {
            objects.remove(((DeleteObjectRequest) invocation.getArgument(0)).key());
            throw new SimulatedProcessDeath();
        }).when(s3).deleteObject(any(DeleteObjectRequest.class));
        assertThatThrownBy(cleanup::processDueBatch).isInstanceOf(SimulatedProcessDeath.class);
        assertThat(count("s3_object_cleanup_queue")).isEqualTo(1);
        assertThat(objects).containsExactly(key(current));
        doAnswer(invocation -> {
            objects.remove(((DeleteObjectRequest) invocation.getArgument(0)).key());
            return DeleteObjectResponse.builder().build();
        }).when(s3).deleteObject(any(DeleteObjectRequest.class));
        cleanup.processDueBatch();
        assertThat(count("s3_object_cleanup_queue")).isZero();
        assertThat(objects).containsExactly(key(current));
    }

    @Test
    void latePutAfterSweepCannotAttachAndGetsDeletedAgain() {
        doAnswer(invocation -> {
            expireUploads(); registry.recoverUnattachedUploads(); cleanup.processDueBatch();
            objects.add(((PutObjectRequest) invocation.getArgument(0)).key());
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThatThrownBy(() -> workflow.replace(17, 10L, file, actor)).isInstanceOf(IllegalStateException.class);
        cleanup.processDueBatch();
        assertThat(objects).containsExactly(key(OLD));
        assertThat(currentUrl()).isEqualTo(OLD);
    }

    @Test
    void aDeadUploaderLateRemotePutIsCaughtByTheRetainedTombstone() {
        var staged = registry.reserve("bucket", "reviews/17-late.jpg", "https://cdn.test/reviews/17-late.jpg", 17);
        expireUploads(); registry.recoverUnattachedUploads(); cleanup.processDueBatch();
        objects.add(staged.key()); // Remote PUT completes after uploader process died.
        expireUploads(); registry.recoverUnattachedUploads(); cleanup.processDueBatch();
        assertThat(objects).containsExactly(key(OLD));
    }

    @Test
    void failedAtomicCleanupWriteRollsBackAttachmentAndQueuesTheStagedObject() {
        doThrow(new IllegalStateException("cleanup insert failed")).when(uploads).enqueueReplacedReviewPhoto(OLD, 17);
        assertThatThrownBy(() -> workflow.replace(17, 10L, file, actor)).isInstanceOf(IllegalStateException.class);
        assertThat(currentUrl()).isEqualTo(OLD);
        assertThat(jdbc.queryForObject("SELECT upload_state FROM s3_upload_registry", String.class)).isEqualTo("ABANDONED");
        cleanup.processDueBatch();
        assertThat(objects).containsExactly(key(OLD));
    }

    @Test
    void competingUploadsKeepOnlyTheFinalAttachedPhoto() throws Exception {
        guardReadBarrier = new CyclicBarrier(2);
        CyclicBarrier puts = new CyclicBarrier(2);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            objects.add(((PutObjectRequest) invocation.getArgument(0)).key());
            puts.await(10, TimeUnit.SECONDS);
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> workflow.replace(17, 10L, file, actor));
            var second = executor.submit(() -> workflow.replace(17, 10L, file, actor));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).contains(currentUrl());
        }
        assertThat(count("s3_object_cleanup_queue")).isEqualTo(2);
        cleanup.processDueBatch();
        assertThat(objects).containsExactly(key(currentUrl()));
    }

    @Test
    void manualOwnedUrlAliasesQueueTheActualObjectKey() {
        jdbc.update("UPDATE reviews SET review_url = ? WHERE review_id = 17",
                "https://CDN.test:443/reviews/17-%6Fld.jpg?preview=1#photo");
        String url = workflow.replace(17, 10L, file, actor);
        assertThat(jdbc.queryForObject("SELECT object_key FROM s3_object_cleanup_queue", String.class))
                .isEqualTo("reviews/17-old.jpg");
        cleanup.processDueBatch();
        assertThat(objects).containsExactly(key(url));
    }

    @Test
    void ownershipChangeDuringPutCannotCommitAndCleansOnlyNewObject() {
        doAnswer(invocation -> {
            objects.add(((PutObjectRequest) invocation.getArgument(0)).key());
            jdbc.update("UPDATE reviews SET actor_name = 'another-manager' WHERE review_id = 17");
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThatThrownBy(() -> workflow.replace(17, 10L, file, actor)).isInstanceOf(ResponseStatusException.class);
        cleanup.processDueBatch();
        assertThat(currentUrl()).isEqualTo(OLD);
        assertThat(objects).containsExactly(key(OLD));
    }

    @Test
    void deniedActorOrWrongOrderCannotStartPut() {
        var foreign = new UsernamePasswordAuthenticationToken("foreign", "unused", actor.getAuthorities());
        assertThatThrownBy(() -> workflow.replace(17, 10L, file, foreign)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> workflow.replace(17, 999L, file, actor)).isInstanceOf(ReviewPhotoRecords.Unavailable.class);
        verifyNoInteractions(s3);
        assertThat(count("s3_upload_registry")).isZero();
    }

    @Test
    void cleanupRefusesLiveReferenceAndRetirementFencesManualReattachment() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> cleanup.enqueueRequired("bucket", key(OLD), "fixture"));
        cleanup.processDueBatch();
        assertThat(objects).contains(key(OLD));
        jdbc.update("UPDATE reviews SET review_url = NULL WHERE review_id = 17");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                references.requireAssignable("https://CDN.test/reviews/17-old.jpg?preview=1#photo")))
                .isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE s3_object_cleanup_queue SET next_attempt_at = CURRENT_TIMESTAMP(6)");
        cleanup.processDueBatch();
        assertThat(objects).isEmpty();
    }

    private void expireUploads() {
        jdbc.update("UPDATE s3_upload_registry SET expires_at = TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP(6)), next_cleanup_at = TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP(6))");
    }
    private String currentUrl() { return jdbc.queryForObject("SELECT review_url FROM reviews WHERE review_id = 17", String.class); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private String key(String url) { return url.substring("https://cdn.test/".length()); }
    private static class SimulatedProcessDeath extends Error {}
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }
}
