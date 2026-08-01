package com.hunt.otziv.external_review_checks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerRequest;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerResponse;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckPlatform;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckSource;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckStatus;
import com.hunt.otziv.external_review_checks.model.ReviewExternalCheck;
import com.hunt.otziv.external_review_checks.repository.ReviewExternalCheckRepository;
import com.hunt.otziv.external_review_checks.service.ExternalReviewCheckTransactionService.ClaimedCheck;
import com.hunt.otziv.performers.service.PerformerAssignmentService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExternalReviewCheckTransactionServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-01T10:00:00Z"),
            ZoneOffset.UTC
    );
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 10, 0);
    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";

    @Test
    void claimUsesAtomicCompareAndSetAndReturnsImmutableWorkerInput() {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        ReviewExternalCheck candidate = check(42L, review, ExternalReviewCheckStatus.PENDING);
        candidate.setAttemptCount(1);
        candidate.setCheckAfter(NOW.minusMinutes(1));
        candidate.setErrorMessage("previous error");

        ReviewExternalCheck claimed = check(42L, review, ExternalReviewCheckStatus.CHECKING);
        claimed.setAttemptCount(2);
        claimed.setProcessingToken(TOKEN);
        claimed.setProcessingOwner("node-a");
        claimed.setProcessingStartedAt(NOW);
        claimed.setProcessingLeaseUntil(NOW.plusMinutes(5));

        when(fixture.checkRepository.findById(42L)).thenReturn(Optional.of(candidate));
        when(fixture.checkRepository.tryClaim(
                eq(42L),
                eq("PENDING"),
                eq(1),
                eq(5),
                eq(TOKEN),
                eq("node-a"),
                eq(NOW),
                eq(NOW.plusMinutes(5))
        )).thenReturn(1);
        when(fixture.checkRepository.findByIdForProcessing(42L)).thenReturn(Optional.of(claimed));
        when(fixture.performerAssignmentService.textForExternalCheck(review)).thenReturn("performer final text");

        ClaimedCheck result = fixture.service.claim(42L).orElseThrow();

        assertThat(result.processingToken()).isEqualTo(TOKEN);
        assertThat(result.previousStatus()).isEqualTo(ExternalReviewCheckStatus.PENDING);
        assertThat(result.previousCheckAfter()).isEqualTo(NOW.minusMinutes(1));
        assertThat(result.previousErrorMessage()).isEqualTo("previous error");
        assertThat(result.request()).isEqualTo(new ExternalReviewWorkerRequest(
                42L,
                7L,
                "YANDEX",
                "https://yandex.ru/maps/org/7",
                "performer final text"
        ));
        assertThat(result.toString())
                .doesNotContain(TOKEN, "performer final text", "https://yandex.ru");
    }

    @Test
    void losingNodeCannotBuildWorkerRequestAfterAtomicClaimLoss() {
        Fixture fixture = new Fixture();
        ReviewExternalCheck candidate = check(
                42L,
                review(7L),
                ExternalReviewCheckStatus.PENDING
        );
        candidate.setCheckAfter(NOW.minusSeconds(1));
        when(fixture.checkRepository.findById(42L)).thenReturn(Optional.of(candidate));
        when(fixture.checkRepository.tryClaim(
                eq(42L),
                eq("PENDING"),
                eq(0),
                eq(5),
                eq(TOKEN),
                eq("node-a"),
                eq(NOW),
                eq(NOW.plusMinutes(5))
        )).thenReturn(0);

        assertThat(fixture.service.claim(42L)).isEmpty();
        verify(fixture.checkRepository, never()).findByIdForProcessing(42L);
        verify(fixture.performerAssignmentService, never()).textForExternalCheck(candidate.getReview());
    }

    @Test
    void completionIsFencedByBothTokenAndActiveLease() {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        ReviewExternalCheck stale = check(42L, review, ExternalReviewCheckStatus.CHECKING);
        stale.setProcessingToken(TOKEN);
        stale.setProcessingLeaseUntil(NOW.minusNanos(1));
        when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN)).thenReturn(Optional.of(stale));

        boolean completed = fixture.service.complete(
                claim(),
                new ExternalReviewWorkerResponse(
                        42L,
                        "CONFIRMED",
                        0.99,
                        "match",
                        null,
                        null,
                        null,
                        "trace"
                ),
                null
        );

        assertThat(completed).isFalse();
        verify(fixture.checkRepository, never()).save(stale);
        verify(fixture.reviewRepository, never()).save(review);
    }

    @Test
    void killSwitchReleaseRestoresQueuedStateWithoutConsumingAttempt() {
        Fixture fixture = new Fixture();
        ReviewExternalCheck checking = check(
                42L,
                review(7L),
                ExternalReviewCheckStatus.CHECKING
        );
        checking.setAttemptCount(3);
        checking.setProcessingToken(TOKEN);
        checking.setProcessingOwner("node-a");
        checking.setProcessingStartedAt(NOW);
        checking.setProcessingLeaseUntil(NOW.plusMinutes(5));
        when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN))
                .thenReturn(Optional.of(checking));

        ClaimedCheck claim = new ClaimedCheck(
                42L,
                7L,
                TOKEN,
                ExternalReviewCheckStatus.NOT_FOUND,
                NOW.plusHours(3),
                "not found before claim",
                claim().request()
        );

        assertThat(fixture.service.releaseUnconsumed(claim)).isTrue();
        assertThat(checking.getStatus()).isEqualTo(ExternalReviewCheckStatus.NOT_FOUND);
        assertThat(checking.getAttemptCount()).isEqualTo(2);
        assertThat(checking.getCheckAfter()).isEqualTo(NOW.plusHours(3));
        assertThat(checking.getErrorMessage()).isEqualTo("not found before claim");
        assertThat(checking.getProcessingToken()).isNull();
        assertThat(checking.getProcessingOwner()).isNull();
        assertThat(checking.getProcessingStartedAt()).isNull();
        assertThat(checking.getProcessingLeaseUntil()).isNull();
    }

    @Test
    void automaticDualWriteUsesStableSha256ReviewKey() {
        Fixture fixture = new Fixture();
        Review review = review(91L);
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(91L))
                .thenReturn(Optional.of(review));
        when(fixture.reviewRepository.findByIdForDto(91L)).thenReturn(Optional.of(review));

        assertThat(fixture.service.createAutomaticCheck(91L)).isTrue();
        assertThat(fixture.service.createAutomaticCheck(91L)).isTrue();

        ArgumentCaptor<ReviewExternalCheck> captor = ArgumentCaptor.forClass(ReviewExternalCheck.class);
        verify(fixture.checkRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        ReviewExternalCheck first = captor.getAllValues().get(0);
        ReviewExternalCheck second = captor.getAllValues().get(1);
        assertThat(first.getSource()).isEqualTo(ExternalReviewCheckSource.AUTO_SCREENSHOT);
        assertThat(first.getDeduplicationKeyHash()).hasSize(32);
        assertThat(second.getDeduplicationKeyHash())
                .containsExactly(first.getDeduplicationKeyHash());
    }

    @Test
    void automaticCreationStopsWhenManualCheckAppearedAfterCandidateScan() {
        Fixture fixture = new Fixture();
        Review review = review(91L);
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(91L))
                .thenReturn(Optional.of(review));
        when(fixture.checkRepository.findLatestIdByReviewId(91L))
                .thenReturn(Optional.of(900L));

        assertThat(fixture.service.createAutomaticCheck(91L)).isFalse();

        verify(fixture.reviewRepository, never()).findByIdForDto(91L);
        verify(fixture.checkRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void manualCreationAlsoDualWritesANonReusableDedupHash() {
        Fixture fixture = new Fixture();
        Review review = review(91L);
        Order order = new Order();
        order.setId(12L);
        order.setFilial(review.getFilial());
        OrderDetails details = new OrderDetails();
        details.setOrder(order);
        review.setOrderDetails(details);
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(91L))
                .thenReturn(Optional.of(review));
        when(fixture.reviewRepository.findByIdForDto(91L)).thenReturn(Optional.of(review));

        ReviewExternalCheck first = fixture.service.createManualCheck(12L, 91L);
        ReviewExternalCheck second = fixture.service.createManualCheck(12L, 91L);

        assertThat(first.getSource()).isEqualTo(ExternalReviewCheckSource.MANUAL);
        assertThat(first.getDeduplicationKeyHash()).hasSize(32);
        assertThat(second.getDeduplicationKeyHash()).hasSize(32);
        assertThat(second.getDeduplicationKeyHash())
                .isNotEqualTo(first.getDeduplicationKeyHash());
    }

    @Test
    void completionNeverPersistsRawWorkerErrorMessage() {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        ReviewExternalCheck checking = check(42L, review, ExternalReviewCheckStatus.CHECKING);
        checking.setAttemptCount(1);
        checking.setProcessingToken(TOKEN);
        checking.setProcessingOwner("node-a");
        checking.setProcessingStartedAt(NOW);
        checking.setProcessingLeaseUntil(NOW.plusMinutes(5));
        checking.setScreenshotKey("external-review-checks/old.png");
        checking.setScreenshotUrl("https://cdn.example/old.png");
        checking.setConfidence(java.math.BigDecimal.ONE);
        checking.setMatchedTextExcerpt("old evidence");
        when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN))
                .thenReturn(Optional.of(checking));
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(7L))
                .thenReturn(Optional.of(review));
        when(fixture.checkRepository.findLatestIdByReviewId(7L))
                .thenReturn(Optional.of(42L));

        boolean completed = fixture.service.complete(
                claim(),
                new ExternalReviewWorkerResponse(
                        42L,
                        "ERROR",
                        null,
                        null,
                        null,
                        null,
                        "upstream failed with token=super-secret",
                        "trace-42"
                ),
                null
        );

        assertThat(completed).isTrue();
        assertThat(checking.getErrorMessage()).isEqualTo("worker_status_error");
        assertThat(checking.getErrorMessage()).doesNotContain("super-secret");
        assertThat(checking.getScreenshotKey()).isNull();
        assertThat(checking.getScreenshotUrl()).isNull();
        assertThat(checking.getConfidence()).isNull();
        assertThat(checking.getMatchedTextExcerpt()).isNull();
        assertThat(review.getExternalConfirmScreenshotUrl()).isNull();
        assertThat(checking.getProcessingToken()).isNull();
        assertThat(checking.getWorkerTraceId())
                .startsWith("sha256:")
                .doesNotContain("trace-42");
    }

    @Test
    void expiredCheckingClaimAtRetryLimitIsRecoveredAsTerminalError() {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        ReviewExternalCheck checking = check(42L, review, ExternalReviewCheckStatus.CHECKING);
        checking.setAttemptCount(5);
        checking.setProcessingToken(TOKEN);
        checking.setProcessingOwner("dead-node");
        checking.setProcessingStartedAt(NOW.minusMinutes(10));
        checking.setProcessingLeaseUntil(NOW.minusMinutes(5));
        when(fixture.checkRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(checking));
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(7L))
                .thenReturn(Optional.of(review));
        when(fixture.checkRepository.findLatestIdByReviewId(7L))
                .thenReturn(Optional.of(42L));

        assertThat(fixture.service.recoverExhaustedClaim(42L)).isTrue();
        assertThat(checking.getStatus()).isEqualTo(ExternalReviewCheckStatus.ERROR);
        assertThat(checking.getProcessingToken()).isNull();
        assertThat(checking.getProcessingOwner()).isNull();
        assertThat(checking.getProcessingStartedAt()).isNull();
        assertThat(checking.getProcessingLeaseUntil()).isNull();
        assertThat(review.getExternalConfirmStatus()).isEqualTo("ERROR");
        verify(fixture.checkRepository).save(checking);
        verify(fixture.reviewRepository).save(review);
    }

    @Test
    void olderManualCheckCannotOverwriteAggregateOfNewerCheck() {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        review.setExternalConfirmStatus("PENDING");
        ReviewExternalCheck older = activeChecking(42L, review);
        when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN))
                .thenReturn(Optional.of(older));
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(7L))
                .thenReturn(Optional.of(review));
        when(fixture.checkRepository.findLatestIdByReviewId(7L))
                .thenReturn(Optional.of(43L));

        assertThat(fixture.service.complete(
                claim(),
                workerResponse("CONFIRMED", 0.99, null, "trace-older"),
                null
        )).isTrue();

        assertThat(older.getStatus()).isEqualTo(ExternalReviewCheckStatus.CONFIRMED);
        assertThat(review.getExternalConfirmStatus()).isEqualTo("PENDING");
        verify(fixture.reviewRepository, never()).save(review);
    }

    @Test
    void latestCheckUpdatesAggregateWhileHoldingNarrowReviewLock() {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        ReviewExternalCheck latest = activeChecking(42L, review);
        when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN))
                .thenReturn(Optional.of(latest));
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(7L))
                .thenReturn(Optional.of(review));
        when(fixture.checkRepository.findLatestIdByReviewId(7L))
                .thenReturn(Optional.of(42L));

        assertThat(fixture.service.complete(
                claim(),
                workerResponse("CONFIRMED", 0.99, null, "trace-latest"),
                null
        )).isTrue();

        assertThat(review.getExternalConfirmStatus()).isEqualTo("CONFIRMED");
        verify(fixture.reviewRepository).findBaseByIdForExternalCheckUpdate(7L);
        verify(fixture.reviewRepository).save(review);
    }

    @Test
    void nonFiniteConfidenceIsDiscardedWithoutFailingCompletion() {
        for (double invalid : new double[] {
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
        }) {
            Fixture fixture = new Fixture();
            Review review = review(7L);
            ReviewExternalCheck checking = activeChecking(42L, review);
            when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN))
                    .thenReturn(Optional.of(checking));
            when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(7L))
                    .thenReturn(Optional.of(review));
            when(fixture.checkRepository.findLatestIdByReviewId(7L))
                    .thenReturn(Optional.of(42L));

            assertThat(fixture.service.complete(
                    claim(),
                    workerResponse("CONFIRMED", invalid, null, null),
                    null
            )).isTrue();
            assertThat(checking.getConfidence()).isNull();
        }
    }

    @Test
    void malformedWorkerStatusesBecomeErrorWithStableLocalCodes() {
        assertMalformedStatus(null, "worker_status_missing");
        assertMalformedStatus("   ", "worker_status_missing");
        assertMalformedStatus("unexpected", "worker_status_unknown");
        assertMalformedStatus("CHECKING", "worker_status_non_terminal");
    }

    @Test
    void missingWorkerResponseBecomesErrorWithStableLocalCode() {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        ReviewExternalCheck checking = activeChecking(42L, review);
        when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN))
                .thenReturn(Optional.of(checking));
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(7L))
                .thenReturn(Optional.of(review));
        when(fixture.checkRepository.findLatestIdByReviewId(7L))
                .thenReturn(Optional.of(42L));

        assertThat(fixture.service.complete(claim(), null, null)).isTrue();
        assertThat(checking.getStatus()).isEqualTo(ExternalReviewCheckStatus.ERROR);
        assertThat(checking.getErrorMessage()).isEqualTo("worker_response_missing");
    }

    @Test
    void mismatchedWorkerCheckIdCannotBeAppliedAsAValidResult() {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        ReviewExternalCheck checking = activeChecking(42L, review);
        when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN))
                .thenReturn(Optional.of(checking));
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(7L))
                .thenReturn(Optional.of(review));
        when(fixture.checkRepository.findLatestIdByReviewId(7L))
                .thenReturn(Optional.of(42L));
        ExternalReviewWorkerResponse mismatched = new ExternalReviewWorkerResponse(
                999L,
                "CONFIRMED",
                0.99,
                "match",
                null,
                null,
                null,
                "trace"
        );

        assertThat(fixture.service.complete(claim(), mismatched, null)).isTrue();
        assertThat(checking.getStatus()).isEqualTo(ExternalReviewCheckStatus.ERROR);
        assertThat(checking.getErrorMessage()).isEqualTo("worker_check_id_mismatch");
    }

    @Test
    void configuredLeaseIsClampedAboveAllExternalTimeoutsAndMargin() {
        Fixture fixture = new Fixture();
        fixture.properties.setProcessingLease(java.time.Duration.ofSeconds(1));
        fixture.properties.setWorkerConnectTimeout(java.time.Duration.ofSeconds(10));
        fixture.properties.setWorkerReadTimeout(java.time.Duration.ofSeconds(20));
        fixture.properties.setScreenshotUploadTimeout(java.time.Duration.ofSeconds(30));
        fixture.properties.setProcessingLeaseSafetyMargin(java.time.Duration.ofSeconds(40));
        Review review = review(7L);
        ReviewExternalCheck candidate = check(42L, review, ExternalReviewCheckStatus.PENDING);
        candidate.setCheckAfter(NOW.minusSeconds(1));
        when(fixture.checkRepository.findById(42L)).thenReturn(Optional.of(candidate));
        when(fixture.checkRepository.tryClaim(
                eq(42L),
                eq("PENDING"),
                eq(0),
                eq(5),
                eq(TOKEN),
                eq("node-a"),
                eq(NOW),
                eq(NOW.plusSeconds(100))
        )).thenReturn(0);

        assertThat(fixture.service.claim(42L)).isEmpty();
        verify(fixture.checkRepository).tryClaim(
                42L,
                "PENDING",
                0,
                5,
                TOKEN,
                "node-a",
                NOW,
                NOW.plusSeconds(100)
        );
    }

    private void assertMalformedStatus(String status, String expectedCode) {
        Fixture fixture = new Fixture();
        Review review = review(7L);
        ReviewExternalCheck checking = activeChecking(42L, review);
        when(fixture.checkRepository.findClaimedForUpdate(42L, TOKEN))
                .thenReturn(Optional.of(checking));
        when(fixture.reviewRepository.findBaseByIdForExternalCheckUpdate(7L))
                .thenReturn(Optional.of(review));
        when(fixture.checkRepository.findLatestIdByReviewId(7L))
                .thenReturn(Optional.of(42L));

        assertThat(fixture.service.complete(
                claim(),
                workerResponse(status, 0.5, "raw upstream secret", "trace"),
                null
        )).isTrue();
        assertThat(checking.getStatus()).isEqualTo(ExternalReviewCheckStatus.ERROR);
        assertThat(checking.getErrorMessage()).isEqualTo(expectedCode);
    }

    private static ClaimedCheck claim() {
        return new ClaimedCheck(
                42L,
                7L,
                TOKEN,
                ExternalReviewCheckStatus.PENDING,
                NOW.minusMinutes(1),
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

    private static ReviewExternalCheck activeChecking(long id, Review review) {
        ReviewExternalCheck checking = check(id, review, ExternalReviewCheckStatus.CHECKING);
        checking.setAttemptCount(1);
        checking.setProcessingToken(TOKEN);
        checking.setProcessingOwner("node-a");
        checking.setProcessingStartedAt(NOW);
        checking.setProcessingLeaseUntil(NOW.plusMinutes(5));
        return checking;
    }

    private static ExternalReviewWorkerResponse workerResponse(
            String status,
            Double confidence,
            String errorMessage,
            String traceId
    ) {
        return new ExternalReviewWorkerResponse(
                42L,
                status,
                confidence,
                "match",
                null,
                null,
                errorMessage,
                traceId
        );
    }

    private static Review review(long id) {
        Filial filial = new Filial();
        filial.setId(17L);
        filial.setUrl("https://yandex.ru/maps/org/7");
        Review review = new Review();
        review.setId(id);
        review.setText("review text");
        review.setPublish(true);
        review.setPublishedMarkedAt(NOW.minusDays(4));
        review.setExternalConfirmStatus("PENDING");
        review.setFilial(filial);
        return review;
    }

    private static ReviewExternalCheck check(
            long id,
            Review review,
            ExternalReviewCheckStatus status
    ) {
        ReviewExternalCheck check = new ReviewExternalCheck();
        check.setId(id);
        check.setReview(review);
        check.setStatus(status);
        check.setPlatform(ExternalReviewCheckPlatform.YANDEX);
        check.setSource(ExternalReviewCheckSource.AUTO_SCREENSHOT);
        check.setFilialUrl("https://yandex.ru/maps/org/7");
        return check;
    }

    private static final class Fixture {
        private final ReviewExternalCheckRepository checkRepository = mock(ReviewExternalCheckRepository.class);
        private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
        private final PerformerAssignmentService performerAssignmentService = mock(PerformerAssignmentService.class);
        private final ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        private final ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        private final ExternalReviewCheckTransactionService service;

        private Fixture() {
            properties.setEnabled(true);
            properties.setMaxAttempts(5);
            properties.setProcessingLease(java.time.Duration.ofMinutes(5));
            when(runtimeSwitch.isEnabled()).thenReturn(true);
            service = new ExternalReviewCheckTransactionService(
                    checkRepository,
                    reviewRepository,
                    performerAssignmentService,
                    properties,
                    runtimeSwitch,
                    CLOCK,
                    () -> TOKEN,
                    "node-a"
            );
        }
    }
}
