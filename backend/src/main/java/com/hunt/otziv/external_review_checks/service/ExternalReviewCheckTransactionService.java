package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.external_review_checks.config.ExternalReviewTimeoutPolicy;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerRequest;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerResponse;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckPlatform;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckSource;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckStatus;
import com.hunt.otziv.external_review_checks.model.ReviewExternalCheck;
import com.hunt.otziv.external_review_checks.repository.ReviewExternalCheckRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.performers.service.PerformerAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every short database transaction in the external-review worker flow.
 * The orchestrator deliberately performs the worker and S3 calls outside this
 * bean so no connection or row lock is held while external I/O is in flight.
 */
@Service
public class ExternalReviewCheckTransactionService {

    private static final int DEDUP_HASH_BYTES = 32;

    private final ReviewExternalCheckRepository checkRepository;
    private final ReviewRepository reviewRepository;
    private final PerformerAssignmentService performerAssignmentService;
    private final ExternalReviewCheckProperties properties;
    private final ExternalReviewCheckRuntimeSwitch runtimeSwitch;
    private final Clock clock;
    private final Supplier<String> processingTokenSupplier;
    private final String processingOwner;

    @Autowired
    public ExternalReviewCheckTransactionService(
            ReviewExternalCheckRepository checkRepository,
            ReviewRepository reviewRepository,
            PerformerAssignmentService performerAssignmentService,
            ExternalReviewCheckProperties properties,
            ExternalReviewCheckRuntimeSwitch runtimeSwitch
    ) {
        this(
                checkRepository,
                reviewRepository,
                performerAssignmentService,
                properties,
                runtimeSwitch,
                Clock.systemDefaultZone(),
                () -> UUID.randomUUID().toString(),
                "external-review-" + UUID.randomUUID()
        );
    }

    ExternalReviewCheckTransactionService(
            ReviewExternalCheckRepository checkRepository,
            ReviewRepository reviewRepository,
            PerformerAssignmentService performerAssignmentService,
            ExternalReviewCheckProperties properties,
            ExternalReviewCheckRuntimeSwitch runtimeSwitch,
            Clock clock,
            Supplier<String> processingTokenSupplier,
            String processingOwner
    ) {
        this.checkRepository = Objects.requireNonNull(checkRepository, "checkRepository");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository");
        this.performerAssignmentService = Objects.requireNonNull(
                performerAssignmentService,
                "performerAssignmentService"
        );
        this.properties = Objects.requireNonNull(properties, "properties");
        this.runtimeSwitch = Objects.requireNonNull(runtimeSwitch, "runtimeSwitch");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.processingTokenSupplier = Objects.requireNonNull(
                processingTokenSupplier,
                "processingTokenSupplier"
        );
        this.processingOwner = limitedRequired(processingOwner, 128, "processingOwner");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean createAutomaticCheck(Long reviewId) {
        if (!runtimeSwitch.isEnabled() || reviewId == null || reviewId <= 0) {
            return false;
        }

        if (reviewRepository.findBaseByIdForExternalCheckUpdate(reviewId).isEmpty()) {
            return false;
        }
        // The candidate scan uses NOT EXISTS for every check source. Repeat
        // that invariant under the review row lock so a concurrent manual
        // insert cannot be followed by a second automatic provider call.
        if (checkRepository.findLatestIdByReviewId(reviewId).isPresent()) {
            return false;
        }
        Review review = reviewRepository.findByIdForDto(reviewId).orElse(null);
        if (!isAutomaticCandidate(review)) {
            return false;
        }
        Filial filial = filial(review);
        String filialUrl = filial != null ? safe(filial.getUrl()) : "";
        if (filialUrl.isBlank()) {
            return false;
        }

        byte[] automaticDedupHash = dedupHash("external-review:auto:v1:review:" + reviewId);
        if (checkRepository.existsByDeduplicationKeyHash(automaticDedupHash)) {
            return false;
        }
        ReviewExternalCheck check = newCheck(
                review,
                order(review).map(Order::getId).orElse(null),
                filial,
                filialUrl,
                ExternalReviewCheckSource.AUTO_SCREENSHOT,
                automaticDedupHash
        );
        checkRepository.save(check);
        if (!"CONFIRMED".equals(review.getExternalConfirmStatus())) {
            review.setExternalConfirmStatus(ExternalReviewCheckStatus.PENDING.name());
            reviewRepository.save(review);
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewExternalCheck createManualCheck(Long orderId, Long reviewId) {
        requireEnabled();
        reviewRepository.findBaseByIdForExternalCheckUpdate(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Отзыв не найден"));
        Review review = reviewRepository.findByIdForDto(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Отзыв не найден"));
        Order order = order(review)
                .orElseThrow(() -> new IllegalArgumentException("Заказ отзыва не найден"));
        if (!Objects.equals(order.getId(), orderId)) {
            throw new IllegalArgumentException("Отзыв не относится к этому заказу");
        }
        if (!review.isPublish()) {
            throw new IllegalStateException("Проверить можно только опубликованный отзыв");
        }
        if (!hasText(review.getText())) {
            throw new IllegalStateException("У отзыва нет текста для проверки");
        }

        Filial filial = filial(review);
        String filialUrl = filial != null ? safe(filial.getUrl()) : "";
        if (filialUrl.isBlank()) {
            throw new IllegalStateException("У филиала заказа не указана ссылка на карточку");
        }

        // There is no public idempotency key for this legacy manual command.
        // A random event key keeps repeated manual checks compatible while new
        // rows still participate in the nullable hash dual-write rollout.
        byte[] dedupHash = dedupHash(
                "external-review:manual:v1:review:" + reviewId + ":" + UUID.randomUUID()
        );
        ReviewExternalCheck check = newCheck(
                review,
                order.getId(),
                filial,
                filialUrl,
                ExternalReviewCheckSource.MANUAL,
                dedupHash
        );
        checkRepository.save(check);
        review.setExternalConfirmStatus(ExternalReviewCheckStatus.PENDING.name());
        reviewRepository.save(review);
        return check;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedCheck> claim(Long checkId) {
        if (!runtimeSwitch.isEnabled() || checkId == null || checkId <= 0) {
            return Optional.empty();
        }

        ReviewExternalCheck candidate = checkRepository.findById(checkId).orElse(null);
        if (candidate == null || candidate.getStatus() == null) {
            return Optional.empty();
        }
        ExternalReviewCheckStatus previousStatus = candidate.getStatus();
        LocalDateTime previousCheckAfter = candidate.getCheckAfter();
        String previousErrorMessage = candidate.getErrorMessage();
        int previousAttemptCount = candidate.getAttemptCount();

        // Repeat the switch check immediately before the write. A flip after
        // this point is handled by releaseUnconsumed before any worker call.
        if (!runtimeSwitch.isEnabled()) {
            return Optional.empty();
        }

        LocalDateTime now = now();
        String token = nextProcessingToken();
        LocalDateTime leaseUntil = now.plus(processingLease());
        int changed = checkRepository.tryClaim(
                checkId,
                previousStatus.name(),
                previousAttemptCount,
                Math.max(1, properties.getMaxAttempts()),
                token,
                processingOwner,
                now,
                leaseUntil
        );
        if (changed != 1) {
            return Optional.empty();
        }

        ReviewExternalCheck claimed = checkRepository.findByIdForProcessing(checkId)
                .orElseThrow(() -> new IllegalStateException("Claimed external review check disappeared"));
        if (!Objects.equals(token, claimed.getProcessingToken())) {
            throw new IllegalStateException("External review check claim token was not persisted");
        }
        Review review = claimed.getReview();
        ExternalReviewWorkerRequest request = new ExternalReviewWorkerRequest(
                claimed.getId(),
                review.getId(),
                claimed.getPlatform().name(),
                claimed.getFilialUrl(),
                performerAssignmentService.textForExternalCheck(review)
        );
        return Optional.of(new ClaimedCheck(
                claimed.getId(),
                review.getId(),
                token,
                previousStatus,
                previousCheckAfter,
                previousErrorMessage,
                candidate.getScreenshotKey(),
                request
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(
            ClaimedCheck claim,
            ExternalReviewWorkerResponse response,
            ExternalReviewScreenshotStorage.StoredScreenshot screenshot
    ) {
        LocalDateTime now = now();
        ReviewExternalCheck check = ownedActiveClaim(claim, now).orElse(null);
        if (check == null) {
            return false;
        }

        ExternalReviewWorkerResponsePolicy.Outcome outcome =
                ExternalReviewWorkerResponsePolicy.evaluate(response, check.getId());
        ExternalReviewCheckStatus status = outcome.status();
        check.setStatus(status);
        check.setCheckedAt(now);
        check.setConfidence(outcome.evidenceAccepted()
                ? toConfidence(response.confidence())
                : null);
        check.setMatchedTextExcerpt(outcome.evidenceAccepted()
                ? truncate(response.matchedTextExcerpt(), 1000)
                : null);
        // A row is reused for retries. Evidence from an older attempt must not
        // survive a response without fresh accepted evidence.
        check.setScreenshotKey(null);
        check.setScreenshotUrl(null);
        // The worker is an external trust boundary. Its free-form error may
        // contain an upstream exception, URL credentials or page content, so
        // persist only a stable local code.
        check.setErrorMessage(outcome.errorCode() != null
                ? outcome.errorCode()
                : response != null && hasText(response.errorMessage())
                        ? "worker_reported_error"
                        : null);
        check.setWorkerTraceId(traceFingerprint(response != null ? response.traceId() : null));
        if (outcome.evidenceAccepted() && screenshot != null) {
            check.setScreenshotKey(screenshot.key());
            check.setScreenshotUrl(screenshot.url());
        }

        scheduleNextAttempt(check, status, now);
        clearProcessingClaim(check);
        applyAggregateStatus(check, now);
        checkRepository.save(check);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(ClaimedCheck claim, String failureCode) {
        LocalDateTime now = now();
        ReviewExternalCheck check = ownedActiveClaim(claim, now).orElse(null);
        if (check == null) {
            return false;
        }

        check.setStatus(ExternalReviewCheckStatus.ERROR);
        check.setCheckedAt(now);
        clearAttemptEvidence(check);
        check.setErrorMessage(safeWorkerFailureCode(failureCode));
        scheduleNextAttempt(check, ExternalReviewCheckStatus.ERROR, now);
        clearProcessingClaim(check);
        applyAggregateStatus(check, now);
        checkRepository.save(check);
        return true;
    }

    /**
     * Releases a locally-disabled claim without consuming the attempt. The
     * token guard prevents this rollback from overwriting a newer claimant.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean releaseUnconsumed(ClaimedCheck claim) {
        if (claim == null) {
            return false;
        }
        ReviewExternalCheck check = checkRepository.findClaimedForUpdate(
                claim.checkId(),
                claim.processingToken()
        ).orElse(null);
        if (check == null || check.getStatus() != ExternalReviewCheckStatus.CHECKING) {
            return false;
        }

        check.setStatus(claim.previousStatus());
        check.setCheckAfter(claim.previousCheckAfter());
        check.setErrorMessage(claim.previousErrorMessage());
        check.setAttemptCount(Math.max(0, check.getAttemptCount() - 1));
        clearProcessingClaim(check);
        checkRepository.save(check);
        return true;
    }

    /**
     * CHECKING rows that already exhausted their retry budget cannot be
     * reclaimed. Convert an expired/legacy lease to a visible terminal error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverExhaustedClaim(Long checkId) {
        if (!runtimeSwitch.isEnabled() || checkId == null || checkId <= 0) {
            return false;
        }
        ReviewExternalCheck check = checkRepository.findByIdForUpdate(checkId).orElse(null);
        LocalDateTime now = now();
        if (check == null
                || check.getStatus() != ExternalReviewCheckStatus.CHECKING
                || check.getAttemptCount() < Math.max(1, properties.getMaxAttempts())
                || !isLeaseExpiredOrLegacy(check, now)) {
            return false;
        }

        check.setStatus(ExternalReviewCheckStatus.ERROR);
        check.setCheckedAt(now);
        check.setCheckAfter(now);
        clearAttemptEvidence(check);
        check.setErrorMessage("Истек lease внешней проверки после исчерпания попыток");
        clearProcessingClaim(check);
        applyAggregateStatus(check, now);
        checkRepository.save(check);
        return true;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ReviewExternalCheck> findForRead(Long checkId) {
        return checkRepository.findByIdForProcessing(checkId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean automaticDedupExists(Long reviewId) {
        return reviewId != null
                && reviewId > 0
                && checkRepository.existsByDeduplicationKeyHash(
                        dedupHash("external-review:auto:v1:review:" + reviewId)
                );
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public LocalDateTime currentDatabaseTime() {
        return now();
    }

    private ReviewExternalCheck newCheck(
            Review review,
            Long orderId,
            Filial filial,
            String filialUrl,
            ExternalReviewCheckSource source,
            byte[] deduplicationKeyHash
    ) {
        return ReviewExternalCheck.builder()
                .review(review)
                .orderId(orderId)
                .filialId(filial != null ? filial.getId() : null)
                .platform(platformFromUrl(filialUrl))
                .source(source)
                .status(ExternalReviewCheckStatus.PENDING)
                .checkAfter(now())
                .filialUrl(filialUrl)
                .attemptCount(0)
                .deduplicationKeyHash(deduplicationKeyHash)
                .build();
    }

    private Optional<ReviewExternalCheck> ownedActiveClaim(ClaimedCheck claim, LocalDateTime now) {
        if (claim == null) {
            return Optional.empty();
        }
        return checkRepository.findClaimedForUpdate(claim.checkId(), claim.processingToken())
                .filter(check -> check.getStatus() == ExternalReviewCheckStatus.CHECKING)
                .filter(check -> check.getProcessingLeaseUntil() != null)
                .filter(check -> check.getProcessingLeaseUntil().isAfter(now));
    }

    private void scheduleNextAttempt(
            ReviewExternalCheck check,
            ExternalReviewCheckStatus status,
            LocalDateTime now
    ) {
        if (status == ExternalReviewCheckStatus.NOT_FOUND
                && check.getAttemptCount() < Math.max(1, properties.getMaxAttempts())) {
            check.setCheckAfter(now.plus(nonNegative(properties.getNotFoundRetryDelay())));
        } else if (status == ExternalReviewCheckStatus.ERROR
                && check.getAttemptCount() < Math.max(1, properties.getMaxAttempts())) {
            check.setCheckAfter(now.plus(nonNegative(properties.getErrorRetryDelay())));
        } else {
            check.setCheckAfter(now);
        }
    }

    private void clearProcessingClaim(ReviewExternalCheck check) {
        check.setProcessingToken(null);
        check.setProcessingOwner(null);
        check.setProcessingStartedAt(null);
        check.setProcessingLeaseUntil(null);
    }

    private void clearAttemptEvidence(ReviewExternalCheck check) {
        check.setConfidence(null);
        check.setMatchedTextExcerpt(null);
        check.setWorkerTraceId(null);
        check.setScreenshotKey(null);
        check.setScreenshotUrl(null);
    }

    private void applyAggregateStatus(ReviewExternalCheck check, LocalDateTime now) {
        Review associatedReview = check.getReview();
        if (associatedReview == null || associatedReview.getId() == null || check.getId() == null) {
            return;
        }
        Long reviewId = associatedReview.getId();
        Review review = reviewRepository.findBaseByIdForExternalCheckUpdate(reviewId)
                .orElseThrow(() -> new IllegalStateException("External review aggregate target disappeared"));
        if (checkRepository.findLatestIdByReviewId(reviewId)
                .filter(latestId -> Objects.equals(latestId, check.getId()))
                .isEmpty()) {
            return;
        }
        ExternalReviewCheckStatus status = check.getStatus();
        review.setExternalConfirmStatus(status.name());
        review.setExternalConfirmScreenshotUrl(check.getScreenshotUrl());
        if (status == ExternalReviewCheckStatus.CONFIRMED) {
            review.setExternalConfirmedAt(check.getCheckedAt() != null ? check.getCheckedAt() : now);
        } else {
            review.setExternalConfirmedAt(null);
        }
        reviewRepository.save(review);
    }

    private boolean isAutomaticCandidate(Review review) {
        return review != null
                && review.isPublish()
                && review.getPublishedMarkedAt() != null
                && !review.getPublishedMarkedAt().isAfter(
                        now().minusDays(Math.max(0, properties.getConfirmationDelayDays()))
                )
                && hasEligibleReviewText(review.getText())
                && !"CONFIRMED".equals(review.getExternalConfirmStatus());
    }

    private boolean hasEligibleReviewText(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.startsWith("текст отзыва")
                && !normalized.startsWith("нужно подставить");
    }

    private boolean isLeaseExpiredOrLegacy(ReviewExternalCheck check, LocalDateTime now) {
        return check.getProcessingToken() == null
                || check.getProcessingLeaseUntil() == null
                || !check.getProcessingLeaseUntil().isAfter(now);
    }

    private Duration processingLease() {
        return ExternalReviewTimeoutPolicy.processingLease(properties);
    }

    private Duration nonNegative(Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }

    private String nextProcessingToken() {
        return limitedRequired(processingTokenSupplier.get(), 36, "processingToken");
    }

    private static String limitedRequired(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private byte[] dedupHash(String canonicalKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalKey.getBytes(StandardCharsets.UTF_8));
            if (digest.length != DEDUP_HASH_BYTES) {
                throw new IllegalStateException("Unexpected SHA-256 digest length");
            }
            return digest;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private BigDecimal toConfidence(Double confidence) {
        if (confidence == null || !Double.isFinite(confidence)) {
            return null;
        }
        double normalized = Math.max(0.0, Math.min(1.0, confidence));
        return BigDecimal.valueOf(normalized);
    }

    private ExternalReviewCheckPlatform platformFromUrl(String url) {
        String normalized = safe(url).toLowerCase(Locale.ROOT);
        if (normalized.contains("2gis.") || normalized.contains("2gis.ru") || normalized.contains("2gis.com")) {
            return ExternalReviewCheckPlatform.TWO_GIS;
        }
        if (normalized.contains("yandex.") || normalized.contains("ya.ru")) {
            return ExternalReviewCheckPlatform.YANDEX;
        }
        if (normalized.contains("google.") || normalized.contains("goo.gl") || normalized.contains("maps.app.goo.gl")) {
            return ExternalReviewCheckPlatform.GOOGLE;
        }
        return ExternalReviewCheckPlatform.UNKNOWN;
    }

    private Optional<Order> order(Review review) {
        return Optional.ofNullable(review)
                .map(Review::getOrderDetails)
                .map(details -> details.getOrder());
    }

    private Filial filial(Review review) {
        Filial orderFilial = order(review).map(Order::getFilial).orElse(null);
        if (orderFilial != null && hasText(orderFilial.getUrl())) {
            return orderFilial;
        }
        return review != null ? review.getFilial() : null;
    }

    private LocalDateTime now() {
        LocalDateTime databaseTime = checkRepository.currentDatabaseTime();
        return databaseTime != null ? databaseTime : LocalDateTime.now(clock);
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String traceFingerprint(String traceId) {
        if (!hasText(traceId)) {
            return null;
        }
        return "sha256:" + HexFormat.of().formatHex(dedupHash(traceId.trim()));
    }

    private String safeWorkerFailureCode(String value) {
        if (value != null
                && value.matches("worker_exception:[A-Za-z0-9_.-]{1,96}")) {
            return value;
        }
        return "worker_exception:unknown";
    }

    private void requireEnabled() {
        if (!runtimeSwitch.isEnabled()) {
            throw new ExternalReviewWorkerDisabledException();
        }
    }

    public record ClaimedCheck(
            Long checkId,
            Long reviewId,
            String processingToken,
            ExternalReviewCheckStatus previousStatus,
            LocalDateTime previousCheckAfter,
            String previousErrorMessage,
            String previousScreenshotKey,
            ExternalReviewWorkerRequest request
    ) {
        public ClaimedCheck(
                Long checkId,
                Long reviewId,
                String processingToken,
                ExternalReviewCheckStatus previousStatus,
                LocalDateTime previousCheckAfter,
                String previousErrorMessage,
                ExternalReviewWorkerRequest request
        ) {
            this(
                    checkId,
                    reviewId,
                    processingToken,
                    previousStatus,
                    previousCheckAfter,
                    previousErrorMessage,
                    null,
                    request
            );
        }

        @Override
        public String toString() {
            return "ClaimedCheck[checkId=" + checkId
                    + ", reviewId=" + reviewId
                    + ", previousStatus=" + previousStatus
                    + "]";
        }
    }

}
