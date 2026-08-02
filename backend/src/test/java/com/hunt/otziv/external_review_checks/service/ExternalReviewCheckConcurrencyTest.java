package com.hunt.otziv.external_review_checks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerRequest;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerResponse;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckStatus;
import com.hunt.otziv.external_review_checks.repository.ReviewExternalCheckRepository;
import com.hunt.otziv.external_review_checks.service.ExternalReviewCheckTransactionService.ClaimedCheck;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ExternalReviewCheckConcurrencyTest {

    @Test
    void twoApplicationNodesCallWorkerOnlyOnceForTheSameCheck() throws Exception {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckProperties properties = enabledProperties();
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        ExternalReviewCheckService firstNode = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                properties,
                runtimeSwitch,
                transactions
        );
        ExternalReviewCheckService secondNode = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                properties,
                runtimeSwitch,
                transactions
        );

        ClaimedCheck claim = claim();
        AtomicBoolean won = new AtomicBoolean();
        when(transactions.claim(42L)).thenAnswer(ignored ->
                won.compareAndSet(false, true) ? Optional.of(claim) : Optional.empty()
        );
        when(workerClient.verify(claim.request())).thenReturn(response());
        when(transactions.complete(eq(claim), any(), isNull())).thenReturn(true);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return firstNode.processOne(42L);
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return secondNode.processOne(42L);
            });
            start.countDown();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        verify(workerClient).verify(claim.request());
        verify(transactions).complete(eq(claim), any(), isNull());
    }

    @Test
    void switchFlipAfterClaimReleasesAttemptBeforeAnyNetworkCall() {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckProperties properties = enabledProperties();
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        AtomicBoolean enabled = new AtomicBoolean(true);
        when(runtimeSwitch.isEnabled()).thenAnswer(ignored -> enabled.get());
        ExternalReviewCheckService service = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                properties,
                runtimeSwitch,
                transactions
        );
        ClaimedCheck claim = claim();
        when(transactions.claim(42L)).thenAnswer(ignored -> {
            enabled.set(false);
            return Optional.of(claim);
        });

        assertThat(service.processOne(42L)).isFalse();

        verify(transactions).releaseUnconsumed(claim);
        verify(workerClient, never()).verify(any());
        verify(screenshotStorage, never()).store(any(), any(), any(), any());
        verify(transactions, never()).fail(any(), any());
        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void switchFlipAfterWorkerSkipsS3ButStillFinalizesTheClaim() {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true, true, false);
        ExternalReviewCheckService service = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                enabledProperties(),
                runtimeSwitch,
                transactions
        );
        ClaimedCheck claim = claim();
        ExternalReviewWorkerResponse response = responseWithScreenshot();
        when(transactions.claim(42L)).thenReturn(Optional.of(claim));
        when(workerClient.verify(claim.request())).thenReturn(response);
        when(transactions.complete(claim, response, null)).thenReturn(true);

        assertThat(service.processOne(42L)).isTrue();

        verify(screenshotStorage, never()).store(any(), any(), any(), any());
        verify(transactions).complete(claim, response, null);
        verify(transactions, never()).releaseUnconsumed(any());
        verify(transactions, never()).fail(any(), any());
    }

    @Test
    void screenshotFailureCannotTurnACompletedWorkerCallIntoProviderRetry() {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        ExternalReviewCheckService service = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                enabledProperties(),
                runtimeSwitch,
                transactions
        );
        ClaimedCheck claim = claim();
        ExternalReviewWorkerResponse response = responseWithScreenshot();
        when(transactions.claim(42L)).thenReturn(Optional.of(claim));
        when(workerClient.verify(claim.request())).thenReturn(response);
        when(screenshotStorage.store(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("raw secret must not be logged"));
        when(transactions.complete(claim, response, null)).thenReturn(true);

        assertThat(service.processOne(42L)).isTrue();

        verify(workerClient).verify(claim.request());
        verify(transactions).complete(claim, response, null);
        verify(transactions, never()).fail(any(), any());
    }

    @Test
    void uploadedScreenshotIsCompensatedWhenFencedCompletionLoses() {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        ExternalReviewCheckService service = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                enabledProperties(),
                runtimeSwitch,
                transactions
        );
        ClaimedCheck claim = claim();
        ExternalReviewWorkerResponse response = responseWithScreenshot();
        ExternalReviewScreenshotStorage.StoredScreenshot stored =
                new ExternalReviewScreenshotStorage.StoredScreenshot("new-key", "https://cdn/new-key");
        when(transactions.claim(42L)).thenReturn(Optional.of(claim));
        when(workerClient.verify(claim.request())).thenReturn(response);
        when(screenshotStorage.store(any(), any(), any(), any())).thenReturn(stored);
        when(transactions.complete(claim, response, stored)).thenReturn(false);

        assertThat(service.processOne(42L)).isTrue();

        verify(screenshotStorage).deleteBestEffort(stored);
    }

    @Test
    void successfulReplacementCleansThePreviousScreenshot() {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        ExternalReviewCheckService service = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                enabledProperties(),
                runtimeSwitch,
                transactions
        );
        ClaimedCheck claim = new ClaimedCheck(
                42L,
                7L,
                "00000000-0000-0000-0000-000000000001",
                ExternalReviewCheckStatus.NOT_FOUND,
                LocalDateTime.of(2026, 8, 1, 9, 0),
                null,
                "old-key",
                claim().request()
        );
        ExternalReviewWorkerResponse response = responseWithScreenshot();
        ExternalReviewScreenshotStorage.StoredScreenshot stored =
                new ExternalReviewScreenshotStorage.StoredScreenshot("new-key", "https://cdn/new-key");
        when(transactions.claim(42L)).thenReturn(Optional.of(claim));
        when(workerClient.verify(claim.request())).thenReturn(response);
        when(screenshotStorage.store(any(), any(), any(), any())).thenReturn(stored);
        when(transactions.complete(claim, response, stored)).thenReturn(true);

        assertThat(service.processOne(42L)).isTrue();

        verify(screenshotStorage).deleteBestEffort("old-key");
    }

    @Test
    void workerExceptionIsPersistedAsLocalTypeCodeWithoutRawMessage() {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        ExternalReviewCheckService service = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                enabledProperties(),
                runtimeSwitch,
                transactions
        );
        ClaimedCheck claim = claim();
        when(transactions.claim(42L)).thenReturn(Optional.of(claim));
        when(workerClient.verify(claim.request()))
                .thenThrow(new IllegalStateException("Authorization: secret-token"));
        when(transactions.fail(claim, "worker_exception:IllegalStateException")).thenReturn(true);

        assertThat(service.processOne(42L)).isTrue();

        verify(transactions).fail(claim, "worker_exception:IllegalStateException");
        verify(screenshotStorage, never()).store(any(), any(), any(), any());
        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void enqueueSwallowsOnlyConfirmedDedupConstraintViolation() {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        when(repository.findCandidateReviewIds(any(), any())).thenReturn(List.of(7L));
        DataIntegrityViolationException dedupViolation = new DataIntegrityViolationException(
                "insert failed",
                new IllegalStateException("duplicate uk_review_external_checks_dedup_hash")
        );
        when(transactions.createAutomaticCheck(7L)).thenThrow(dedupViolation);
        when(transactions.automaticDedupExists(7L)).thenReturn(true);
        ExternalReviewCheckService service = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                enabledProperties(),
                runtimeSwitch,
                transactions
        );

        assertThat(service.enqueueDueCandidates()).isZero();
        verify(transactions).automaticDedupExists(7L);
    }

    @Test
    void enqueueRethrowsOtherIntegrityViolationsWithoutTreatingThemAsDedup() {
        ReviewExternalCheckRepository repository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        when(repository.findCandidateReviewIds(any(), any())).thenReturn(List.of(7L));
        DataIntegrityViolationException unrelated =
                new DataIntegrityViolationException("foreign key violation");
        when(transactions.createAutomaticCheck(7L)).thenThrow(unrelated);
        ExternalReviewCheckService service = new ExternalReviewCheckService(
                repository,
                workerClient,
                screenshotStorage,
                enabledProperties(),
                runtimeSwitch,
                transactions
        );

        assertThatThrownBy(service::enqueueDueCandidates).isSameAs(unrelated);
        verify(transactions, never()).automaticDedupExists(any());
    }

    @Test
    void manualCreationCrossesIntoRequiresNewTransactionalBean() throws Exception {
        Method facadeMethod = ExternalReviewCheckService.class.getMethod(
                "createManualCheck",
                Long.class,
                Long.class
        );
        Method transactionMethod = ExternalReviewCheckTransactionService.class.getMethod(
                "createManualCheck",
                Long.class,
                Long.class
        );

        assertThat(facadeMethod.getAnnotation(Transactional.class)).isNull();
        Transactional transactional = transactionMethod.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void repositoryClaimAndCompletionQueriesEncodeCasAndTokenFence() throws Exception {
        Method claim = ReviewExternalCheckRepository.class.getMethod(
                "tryClaim",
                Long.class,
                String.class,
                int.class,
                int.class,
                String.class,
                String.class,
                LocalDateTime.class,
                LocalDateTime.class
        );
        Query claimQuery = claim.getAnnotation(Query.class);
        assertThat(claim.getAnnotation(Modifying.class)).isNotNull();
        assertThat(claimQuery.nativeQuery()).isTrue();
        assertThat(claimQuery.value()).contains(
                "status = :expectedStatus",
                "attempt_count = :expectedAttemptCount",
                "processing_token = :processingToken",
                "processing_lease_until = :leaseUntil",
                "processing_lease_until <= :now"
        );

        Method completion = ReviewExternalCheckRepository.class.getMethod(
                "findClaimedForUpdate",
                Long.class,
                String.class
        );
        assertThat(completion.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(completion.getAnnotation(Query.class).value())
                .contains("c.processingToken = :processingToken");
    }

    @Test
    void candidateQueryIncludesLegacyNullAggregateStatus() throws Exception {
        Method candidate = ReviewExternalCheckRepository.class.getMethod(
                "findCandidateReviewIds",
                LocalDateTime.class,
                org.springframework.data.domain.Pageable.class
        );

        Query query = candidate.getAnnotation(Query.class);
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains(
                        "r.review_external_confirm_status IS NULL",
                        "OR r.review_external_confirm_status <> 'CONFIRMED'",
                        "LEFT JOIN filial review_f ON review_f.filial_id = r.review_filial",
                        "NULLIF(TRIM(order_f.filial_url), '')",
                        "NULLIF(TRIM(review_f.filial_url), '')"
                )
                .doesNotContain("COALESCE(r.review_external_confirm_status, '')");
    }

    @Test
    void leaseClockComesFromThePrimaryDatabase() throws Exception {
        Method databaseClock = ReviewExternalCheckRepository.class.getMethod(
                "currentDatabaseTime"
        );

        Query query = databaseClock.getAnnotation(Query.class);
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).containsIgnoringCase("CURRENT_TIMESTAMP(6)");
    }

    @Test
    void latestWinsUsesANarrowBaseReviewRowLock() throws Exception {
        Method lockMethod = ReviewRepository.class.getMethod(
                "findBaseByIdForExternalCheckUpdate",
                Long.class
        );

        assertThat(lockMethod.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(lockMethod.getAnnotation(Query.class).value())
                .contains("SELECT r FROM Review r WHERE r.id = :reviewId")
                .doesNotContain("JOIN", "FETCH");
    }

    private static ExternalReviewCheckProperties enabledProperties() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static ClaimedCheck claim() {
        return new ClaimedCheck(
                42L,
                7L,
                "00000000-0000-0000-0000-000000000001",
                ExternalReviewCheckStatus.PENDING,
                LocalDateTime.of(2026, 8, 1, 9, 0),
                null,
                new ExternalReviewWorkerRequest(
                        42L,
                        7L,
                        "YANDEX",
                        "https://yandex.ru/maps/org/7",
                        "review text"
                )
        );
    }

    private static ExternalReviewWorkerResponse response() {
        return new ExternalReviewWorkerResponse(
                42L,
                "CONFIRMED",
                0.99,
                "review text",
                null,
                null,
                null,
                "trace-42"
        );
    }

    private static ExternalReviewWorkerResponse responseWithScreenshot() {
        return new ExternalReviewWorkerResponse(
                42L,
                "CONFIRMED",
                0.99,
                "review text",
                "iVBORw0KGgo=",
                "image/png",
                null,
                "trace-42"
        );
    }
}
