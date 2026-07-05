package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalReviewCheckService {

    private static final List<ExternalReviewCheckStatus> DUE_STATUSES = List.of(
            ExternalReviewCheckStatus.PENDING,
            ExternalReviewCheckStatus.NOT_FOUND,
            ExternalReviewCheckStatus.ERROR
    );

    private final ReviewExternalCheckRepository checkRepository;
    private final ReviewRepository reviewRepository;
    private final ExternalReviewWorkerClient workerClient;
    private final ExternalReviewScreenshotStorage screenshotStorage;
    private final ExternalReviewCheckProperties properties;
    private final PerformerAssignmentService performerAssignmentService;

    @Transactional
    public int enqueueDueCandidates() {
        if (!properties.isEnabled()) {
            return 0;
        }

        LocalDateTime threshold = LocalDateTime.now().minusDays(properties.getConfirmationDelayDays());
        List<Long> reviewIds = checkRepository.findCandidateReviewIds(
                threshold,
                PageRequest.of(0, properties.getBatchSize())
        );

        int created = 0;
        for (Long reviewId : reviewIds) {
            Optional<Review> reviewOptional = reviewRepository.findByIdForDto(reviewId);
            if (reviewOptional.isEmpty()) {
                continue;
            }

            Review review = reviewOptional.get();
            Filial filial = filial(review);
            String filialUrl = filial != null ? safe(filial.getUrl()) : "";
            if (filialUrl.isBlank()) {
                continue;
            }

            ReviewExternalCheck check = ReviewExternalCheck.builder()
                    .review(review)
                    .orderId(order(review).map(Order::getId).orElse(null))
                    .filialId(filial.getId())
                    .platform(platformFromUrl(filialUrl))
                    .source(ExternalReviewCheckSource.AUTO_SCREENSHOT)
                    .status(ExternalReviewCheckStatus.PENDING)
                    .checkAfter(LocalDateTime.now())
                    .filialUrl(filialUrl)
                    .attemptCount(0)
                    .build();
            checkRepository.save(check);
            if (!"CONFIRMED".equals(review.getExternalConfirmStatus())) {
                review.setExternalConfirmStatus(ExternalReviewCheckStatus.PENDING.name());
                reviewRepository.save(review);
            }
            created++;
        }

        if (created > 0) {
            log.info("Поставлено проверок внешнего наличия отзывов: {}", created);
        }
        return created;
    }

    public int processDueChecks() {
        if (!properties.isEnabled()) {
            return 0;
        }

        List<ReviewExternalCheck> checks = checkRepository.findDueChecks(
                DUE_STATUSES,
                LocalDateTime.now(),
                properties.getMaxAttempts(),
                PageRequest.of(0, properties.getBatchSize())
        );

        int processed = 0;
        for (ReviewExternalCheck check : checks) {
            processOne(check.getId());
            processed++;
        }
        return processed;
    }

    @Transactional
    public ReviewExternalCheck createManualCheck(Long orderId, Long reviewId) {
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

        ReviewExternalCheck check = ReviewExternalCheck.builder()
                .review(review)
                .orderId(order.getId())
                .filialId(filial.getId())
                .platform(platformFromUrl(filialUrl))
                .source(ExternalReviewCheckSource.MANUAL)
                .status(ExternalReviewCheckStatus.PENDING)
                .checkAfter(LocalDateTime.now())
                .filialUrl(filialUrl)
                .attemptCount(0)
                .build();
        checkRepository.save(check);
        review.setExternalConfirmStatus(ExternalReviewCheckStatus.PENDING.name());
        reviewRepository.save(review);
        return check;
    }

    public ReviewExternalCheck runManualCheck(Long orderId, Long reviewId) {
        ReviewExternalCheck check = createManualCheck(orderId, reviewId);
        processOne(check.getId());
        return checkRepository.findByIdForProcessing(check.getId()).orElse(check);
    }

    public void processOne(Long checkId) {
        ReviewExternalCheck check = markChecking(checkId);
        if (check == null) {
            return;
        }

        try {
            check = checkRepository.findByIdForProcessing(checkId).orElseThrow();
            Review review = check.getReview();
            ExternalReviewWorkerResponse response = workerClient.verify(new ExternalReviewWorkerRequest(
                    check.getId(),
                    review.getId(),
                    check.getPlatform().name(),
                    check.getFilialUrl(),
                    performerAssignmentService.textForExternalCheck(review)
            ));
            completeFromWorker(check.getId(), response);
        } catch (Exception e) {
            log.warn("Проверка внешнего отзыва завершилась ошибкой: checkId={}", checkId, e);
            completeWithError(check.getId(), e.getMessage());
        }
    }

    @Transactional
    protected ReviewExternalCheck markChecking(Long checkId) {
        Optional<ReviewExternalCheck> optional = checkRepository.findByIdForProcessing(checkId);
        if (optional.isEmpty()) {
            return null;
        }
        ReviewExternalCheck check = optional.get();
        if (!DUE_STATUSES.contains(check.getStatus())) {
            return null;
        }
        check.setStatus(ExternalReviewCheckStatus.CHECKING);
        check.setAttemptCount(check.getAttemptCount() + 1);
        check.setErrorMessage(null);
        return checkRepository.save(check);
    }

    @Transactional
    protected void completeFromWorker(Long checkId, ExternalReviewWorkerResponse response) {
        ReviewExternalCheck check = checkRepository.findByIdForProcessing(checkId).orElseThrow();
        ExternalReviewCheckStatus status = parseStatus(response != null ? response.status() : null);
        check.setStatus(status);
        check.setCheckedAt(LocalDateTime.now());
        check.setConfidence(toConfidence(response != null ? response.confidence() : null));
        check.setMatchedTextExcerpt(truncate(response != null ? response.matchedTextExcerpt() : null, 1000));
        check.setErrorMessage(truncate(response != null ? response.errorMessage() : null, 1000));
        check.setWorkerTraceId(truncate(response != null ? response.traceId() : null, 128));

        if (response != null && response.screenshotBase64() != null && !response.screenshotBase64().isBlank()) {
            ExternalReviewScreenshotStorage.StoredScreenshot screenshot = screenshotStorage.store(
                    check.getId(),
                    check.getReview().getId(),
                    response.screenshotBase64(),
                    response.screenshotContentType()
            );
            if (screenshot != null) {
                check.setScreenshotKey(screenshot.key());
                check.setScreenshotUrl(screenshot.url());
            }
        }

        if (status == ExternalReviewCheckStatus.NOT_FOUND && check.getAttemptCount() < properties.getMaxAttempts()) {
            check.setCheckAfter(LocalDateTime.now().plus(properties.getNotFoundRetryDelay()));
        } else if (status == ExternalReviewCheckStatus.ERROR && check.getAttemptCount() < properties.getMaxAttempts()) {
            check.setCheckAfter(LocalDateTime.now().plus(properties.getErrorRetryDelay()));
        } else {
            check.setCheckAfter(LocalDateTime.now());
        }

        applyAggregateStatus(check);
        checkRepository.save(check);
    }

    @Transactional
    protected void completeWithError(Long checkId, String message) {
        ReviewExternalCheck check = checkRepository.findByIdForProcessing(checkId).orElseThrow();
        check.setStatus(ExternalReviewCheckStatus.ERROR);
        check.setCheckedAt(LocalDateTime.now());
        check.setErrorMessage(truncate(message, 1000));
        if (check.getAttemptCount() < properties.getMaxAttempts()) {
            check.setCheckAfter(LocalDateTime.now().plus(properties.getErrorRetryDelay()));
        }
        applyAggregateStatus(check);
        checkRepository.save(check);
    }

    private void applyAggregateStatus(ReviewExternalCheck check) {
        Review review = check.getReview();
        if (review == null) {
            return;
        }

        ExternalReviewCheckStatus status = check.getStatus();
        review.setExternalConfirmStatus(status.name());
        review.setExternalConfirmScreenshotUrl(check.getScreenshotUrl());
        if (status == ExternalReviewCheckStatus.CONFIRMED) {
            review.setExternalConfirmedAt(check.getCheckedAt() != null ? check.getCheckedAt() : LocalDateTime.now());
            performerAssignmentService.markVerifiedByReview(review.getId());
        } else {
            review.setExternalConfirmedAt(null);
        }
        reviewRepository.save(review);
    }

    private BigDecimal toConfidence(Double confidence) {
        if (confidence == null) {
            return null;
        }
        double normalized = Math.max(0.0, Math.min(1.0, confidence));
        return BigDecimal.valueOf(normalized);
    }

    private ExternalReviewCheckStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return ExternalReviewCheckStatus.ERROR;
        }
        try {
            return ExternalReviewCheckStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ExternalReviewCheckStatus.ERROR;
        }
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
        Filial orderFilial = order(review)
                .map(Order::getFilial)
                .orElse(null);
        if (orderFilial != null && hasText(orderFilial.getUrl())) {
            return orderFilial;
        }
        return review != null ? review.getFilial() : null;
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
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }
}
